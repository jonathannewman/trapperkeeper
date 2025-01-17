(ns puppetlabs.trapperkeeper.internal
  (:import (clojure.lang ExceptionInfo IFn IDeref)
           (java.lang ArithmeticException NumberFormatException))
  (:require [clojure.tools.logging :as log]
            [beckon]
            [plumbing.graph :as graph]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.trapperkeeper.config :refer [config-service get-in-config]]
            [puppetlabs.trapperkeeper.app :as a]
            [puppetlabs.trapperkeeper.common :as common]
            [puppetlabs.trapperkeeper.services :as s]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.i18n.core :as i18n]
            [schema.core :as schema]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-prot]
            [me.raynes.fs :as fs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def LifeCycleTask
  {:type (schema/enum :restart :shutdown :boot)
   :task-function IFn})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is (eww) a global variable that holds a reference to all of the running
;; Trapperkeeper apps in the process. It can be used when connecting via nrepl
;; to allow you to do useful things, and also may be used for other things
;; (such as signal handling).
(def tk-apps (atom []))

(def max-pending-lifecycle-events 5)

(defn service-graph?
  "Predicate that tests whether or not the argument is a valid trapperkeeper
  service graph."
  [service-graph]
  (and
   (map? service-graph)
   (every? keyword? (keys service-graph))
   (every? (some-fn ifn? service-graph?) (vals service-graph))))

(defn inc-restart-counter!
  "Increments the counter in a restart-file for purposes of supporting HUP behavior"
  [app]
  (when-let
      [restart-file-path (get-in-config (a/get-service app :ConfigService) [:global :restart-file])]
      (try
        (if (fs/exists? restart-file-path)
          (if (and (fs/writeable? restart-file-path) (fs/readable? restart-file-path))
            (spit restart-file-path (inc (Long/parseLong (slurp restart-file-path))))
            (throw (IllegalStateException. (i18n/trs "Restart file {0} is not readable and/or writeable" restart-file-path))))
          (let [dir-path (fs/parent restart-file-path)]
            (fs/mkdirs dir-path)
            (spit restart-file-path "1")))
        (catch ArithmeticException e
          (spit restart-file-path "1")
          (log/debug (i18n/trs "Number of restarts has exceeded Long/MAX_VALUE, resetting file to 1")))
        (catch NumberFormatException e
          (spit restart-file-path "1")
          (log/error (i18n/trs "Restart file is unparseable, resetting file to 1"))))))

(defn validate-service-graph!
  "Validates that a ServiceDefinition contains a valid trapperkeeper service graph.
  Returns the service definition on success; throws an ::invalid-service-graph
  if the graph is invalid."
  [service-def]
  {:post [(satisfies? s/ServiceDefinition %)]}
  (if-not (satisfies? s/ServiceDefinition service-def)
    (throw+ {:type ::invalid-service-graph
             :message (i18n/trs "Invalid service definition; expected a service definition (created via `service` or `defservice`); found: {0}" (pr-str service-def))}))
  (if (service-graph? (s/service-map service-def))
    service-def
    (throw+ {:type ::invalid-service-graph
             :message (i18n/trs "Invalid service graph; service graphs must be nested maps of keywords to functions.  Found: {0}"
                                (s/service-map service-def))})))

(defn parse-missing-required-key
  "Prismatic's graph compilation code throws `ExceptionInfo` objects if required
  keys are missing somewhere in the graph structure.  This includes a map with
  information about what keys were missing.  This function is responsible for
  interpreting one of those error maps to determine whether it implies that the
  trapperkeeper service definition was missing a service function.

  Returns a map containing the name of the service and the name of the missing
  function, or nil if the error map looks like it represents some other kind
  of error."
  [m]
  {:pre [(map? m)]
   :post [(or (nil? %)
              (and (map? %)
                   (contains? % :service-name)
                   (contains? % :service-fn)))]}
  (let [service-name    (first (keys m))
        service-fn-name (first (keys (m service-name)))
        error           (get-in m [service-name service-fn-name])]
    (when (= error 'missing-required-key)
      {:service-name (name service-name)
       :service-fn   (name service-fn-name)})))

(defn handle-prismatic-exception!
  "Takes an ExceptionInfo object that was thrown during a prismatic graph
  compilation / instantiation, and inspects it to see if the error data map
  represents a missing trapperkeeper service or function.  If so, throws a
  more meaningful exception.  If not, re-throws the original exception."
  [e]
  {:pre [(instance? ExceptionInfo e)]}
  (let [data (ex-data e)]
    (condp = (:error data)
      :missing-key
      (throw (RuntimeException. (i18n/trs "Service ''{0}'' not found" (:key data))))

      :does-not-satisfy-schema
      (if-let [error-info (parse-missing-required-key (:failures data))]
        (throw (RuntimeException. (i18n/trs "Service function ''{0}'' not found in service ''{1}''"
                                            (:service-fn error-info)
                                            (:service-name error-info))))
        (throw e))

      (if (sequential? (:error data))
        (let [missing-services (keys (ks/filter-map
                                      (fn [_ v] (= v 'missing-required-key))
                                      (.error (first (:error data)))))]
          (if (= 1 (count missing-services))
            (throw (RuntimeException.
                    (i18n/trs "Service ''{0}'' not found" (first missing-services))))
            (throw (RuntimeException.
                    (i18n/trs "Services ''{0}'' not found" missing-services)))))
        (throw e)))))

(defn compile-graph
  "Given the merged map of services, compile it into a function suitable for instantiation.
  Throws an exception if there is a dependency on a service that is not found in the map."
  [graph-map]
  {:pre  [(service-graph? graph-map)]
   :post [(ifn? %)]}
  (try
    (graph/eager-compile graph-map)
    (catch ExceptionInfo e
      (handle-prismatic-exception! e))))

(defn instantiate
  "Given the compiled graph function, instantiate the application. Throws an exception
  if there is a dependency on a service function that is not found in the graph."
  [graph-fn data]
  {:pre  [(ifn? graph-fn)
          (map? data)]
   :post [(service-graph? %)]}
  (try
    (graph-fn data)
    (catch ExceptionInfo e
      (handle-prismatic-exception! e))))


(schema/defn parse-cli-args! :- common/CLIData
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
  Hard-codes the command-line arguments expected by trapperkeeper to be:
      --debug
      --bootstrap-config <bootstrap file>
      --config <.ini file or directory>
      --plugins <plugins directory>
      --restart-file <restart file>"
  [cli-args :- [(schema/maybe schema/Str)]]
  (let [specs       [["-d" "--debug" (i18n/trs "Turns on debug mode")]
                     ["-b" "--bootstrap-config BOOTSTRAP-CONFIG-FILE" (i18n/trs "Path to bootstrap config file")]
                     ["-c" "--config CONFIG-PATH"
                      (i18n/trs "Path to a configuration file or directory of configuration files, or a comma-separated list of such paths. See the documentation for a list of supported file types.")]
                     ["-p" "--plugins PLUGINS-DIRECTORY" (i18n/trs "Path to directory plugin .jars")]
                     ["-r" "--restart-file RESTART-FILE"
                      (i18n/trs "Path to a file whose contents are incremented each time all of the configured services have been started.")]]
        required    []]
    (first (ks/cli! cli-args specs required))))

(schema/defn ^:always-validate run-lifecycle-fn!
  "Run a lifecycle function for a service.  Required arguments:

  * app-context: the app context atom; can be updated by the lifecycle fn
  * lifecycle-fn: a fn from the Lifecycle protocol
  * lifecycle-fn-name: a string containing the name of the lifecycle fn that
                       is being run.  This is only used to produce a readable
                       error message if an error occurs.
  * service-id: the id of the service that the lifecycle fn is being run on
  * s: the service that the lifecycle fn is being run on"
  [app-context :- (schema/atom a/TrapperkeeperAppContext)
   lifecycle-fn :- IFn
   lifecycle-fn-name :- schema/Str
   service-id :- schema/Keyword
   s :- (schema/protocol s/Service)]
  (let [;; call the lifecycle function on the service, and keep a reference
        ;; to the updated context map that it returns
        updated-ctxt  (lifecycle-fn s (get-in @app-context [:service-contexts service-id] {}))]
    (if-not (map? updated-ctxt)
      (throw (IllegalStateException.
               (i18n/trs "Lifecycle function ''{0}'' for service ''{1}'' must return a context map (got: {2})"
                         lifecycle-fn-name
                         (or (s/service-symbol s) service-id)
                         (pr-str updated-ctxt)))))
    ;; store the updated service context map in the application context atom
    (swap! app-context assoc-in [:service-contexts service-id] updated-ctxt)))

(schema/defn run-lifecycle-fns
  "Run a lifecycle function for all services.  Required arguments:

  * app-context: the app context atom; can be updated by the lifecycle fn
  * lifecycle-fn: a fn from the Lifecycle protocol
  * lifecycle-fn-name: a string containing the name of the lifecycle fn that
                       is being run.  This is only used to produce a readable
                       error message if an error occurs.
  * ordered-services: a list of the services in an order that conforms to
                      their dependency specifications.  This ensures that
                      we call the lifecycle functions in the correct order
                      (i.e. a service can be assured that any services it
                      depends on will have their corresponding lifecycle fn
                      called first.)"
  [app-context :- (schema/atom a/TrapperkeeperAppContext)
   lifecycle-fn :- IFn
   lifecycle-fn-name :- schema/Str
   ordered-services :- a/TrapperkeeperAppOrderedServices]
  (try
    (doseq [[service-id s] ordered-services]
      (log/debug (i18n/trs "Running lifecycle function ''{0}'' for service ''{1}''"
                           lifecycle-fn-name
                           service-id))
      (run-lifecycle-fn! app-context
                         lifecycle-fn
                         lifecycle-fn-name
                         service-id
                         s)
      (log/debug (i18n/trs "Finished running lifecycle function ''{0}'' for service ''{1}''"
                           lifecycle-fn-name
                           service-id)))
    (catch Throwable t
      (log/error t (i18n/trs "Error during service {0}!!!" lifecycle-fn-name))
      (throw t))))

(schema/defn ^:always-validate initialize-lifecycle-worker :- (schema/protocol async-prot/Channel)
  "Initializes a 'worker' which will listen for lifecycle-related tasks and perform
  them on a background thread, to ensure that we aren't executing multiple lifecycle
  tasks simultaneously."
  [lifecycle-channel :- (schema/protocol async-prot/Channel)
   shutdown-channel :- (schema/protocol async-prot/Channel)
   shutdown-reason-promise :- IDeref]
  (log/debug (i18n/trs "Initializing lifecycle worker loop."))
  (async/go-loop []
    (let [[task chan] (async/alts!
                       [shutdown-channel lifecycle-channel]
                       :priority true)]
      (schema/validate LifeCycleTask task)
      (let [{:keys [type task-function]} task]
        (condp #(contains? %1 %2) type
          #{:shutdown}
          (do
            (try
              (log/debug (i18n/trs "Received shutdown command, shutting down services"))
              (async/close! shutdown-channel)
              (async/close! lifecycle-channel)

              (log/debug (i18n/trs "Clearing lifecycle worker channels for shutdown."))
              ;; drain the channels to ensure that there are
              ;; no blocking puts, e.g. if a second shutdown request
              ;; was queued simultaneously
              (ks/while-let [msg (async/poll! shutdown-channel)]
                            (log/debug (i18n/trs "Shutdown in progress, ignoring message on shutdown channel: {0}"
                                                 (:type msg))))
              (ks/while-let [msg (async/poll! lifecycle-channel)]
                            (log/debug (i18n/trs "Shutdown in progress, ignoring message on main lifecycle channel: {0}"
                                                 (:type msg))))

              (task-function)

              (log/debug (i18n/trs "Service shutdown complete, exiting lifecycle worker loop"))
              (catch Exception e
                (log/debug e (i18n/trs "Exception caught during shutdown!"))))
            :done)

          #{:boot :restart}
          (do
            (try
              (log/debug (i18n/trs "Lifecycle worker executing {0} lifecycle task." type))
              (task-function)
              (log/debug (i18n/trs "Lifecycle worker completed {0} lifecycle task; awaiting next task." type))
              (catch Exception e
                (log/debug e (i18n/trs "Exception caught in lifecycle worker loop"))
                (deliver shutdown-reason-promise
                         {:cause :service-error
                          :error e})))
            (recur))

          (do
            (deliver shutdown-reason-promise
                     {:cause :service-error
                      :error (IllegalStateException. (i18n/trs "Unrecognized lifecycle task: %s" task))})
            (recur)))))))

(defn restart-tk-apps
  "Call restart on all tk apps."
  [apps]
  (log/info (i18n/trs "SIGHUP handler restarting TK apps."))
  (doseq [app apps]
    (let [{:keys [lifecycle-channel]} @(a/app-context app)
          restart-fn #(a/restart app)]
      (when-not (async/offer! lifecycle-channel
                              {:type :restart
                               :task-function restart-fn})
        (log/warn (i18n/trs "Ignoring new SIGHUP restart requests; too many requests queued ({0})"
                            max-pending-lifecycle-events))))))

(defn register-sighup-handler
  "Register a handler for SIGHUP that restarts all trapperkeeper apps. The
  default handler terminates the process, so we always overwrite that. This
  function is idempotent."
  ([]
   (register-sighup-handler @tk-apps))
  ([apps]
   (log/debug (i18n/trs "Registering SIGHUP handler for restarting TK apps"))
   (reset! (beckon/signal-atom "HUP") #{(partial restart-tk-apps apps)})))

;;;; Application Shutdown Support
;;;;
;;;; The next section of this namespace
;;;; provides top-level functions to be called by TrapperKeeper internally
;;;; and a shutdown service for application services to utilize.
;;;;
;;;; Performing the actual shutdown is relatively easy, but dealing with
;;;; exceptions during shutdown and providing various ways for services
;;;; to trigger shutdown make up most of the code in this namespace.
;;;;
;;;; During development of this namespace we wanted to ensure that the
;;;; shutdown sequence occurred on the main thread and not on some
;;;; service's worker thread. Because of this, a Clojure `promise` is
;;;; used to pass contextual shutdown information back to the main thread,
;;;; at which point it is responsible for calling back into this namespace
;;;; to perform the appropriate shutdown steps.
;;;;
;;;; As a consequence of the implementation, the main blocking behavior of
;;;; TrapperKeeper is defined here by `wait-for-app-shutdown`. This function
;;;; simply attempts to dereference the above mentioned promise, which
;;;; until it is realized (e.g. by a call to `deliver`) will block, and the
;;;; delivered value returned. This value will contain contextual information
;;;; regarding the cause of the shutdown, and is intended to be passed back
;;;; in to the top-level functions that perform various shutdown steps.

(def exit-request-schema
  "A process exit request like
  {:status 7
   :messages [[\"something for stderr\n\" *err*]]
              [\"something for stdout\n\" *out*]]
              [\"something else for stderr\n\" *err*]]"
  {:status schema/Int
   :messages [[(schema/one schema/Str "message")
               (schema/one java.io.Writer "stream")]]})

(def ^{:private true
       :doc "The possible causes for shutdown to be initiated."}
  shutdown-causes #{:requested :service-error :jvm-shutdown-hook})

(schema/defn request-shutdown*
  "Initiate the normal shutdown of TrapperKeeper. This is asynchronous.
  It is assumed that `wait-for-app-shutdown` has been called and is
  blocking.  Intended to be used by application services (likely their
  worker threads) to programatically trigger application shutdown.
  Note that this is exposed via the `shutdown-service`.  See the
  protocol documentation for addiitonal information."
  ([shutdown-reason-promise]
   (request-shutdown* shutdown-reason-promise {}))
  ([shutdown-reason-promise
    opts :- {(schema/optional-key :puppetlabs.trapperkeeper.core/exit)
             exit-request-schema}]
   (deliver shutdown-reason-promise
            (merge {:cause :requested}
                   (select-keys opts [:puppetlabs.trapperkeeper.core/exit])))))

(defn shutdown-on-error*
  "A higher-order function that is intended to be used as a wrapper around
  some logic `f` in your services. It will wrap your application logic in a
  `try/catch` block that will cause TrapperKeeper to initiate an error shutdown
  if an exception occurs in your block. This is generally intended to be used
  on worker threads that your service may launch.

  If an optional `on-error-fn` is provided, it will be executed if `f` throws
  an exception, but before the primary shutdown sequence begins."
  ([shutdown-reason-promise app-context svc-id f]
   (shutdown-on-error* shutdown-reason-promise app-context svc-id f nil))
  ([shutdown-reason-promise app-context svc-id f on-error-fn]
   (try
     ; This schema check would normally be handled via schematizing the fn itself;
     ; however, this function needs to never throw an exception - since it is often
     ; called as a wrapper around everything inside a `future`, it is important
     ; that this function ; never throw anything (like a schema validation error),
     ; since it is likely to just get lost in a `future`.  Instead,
     ; invalid arguments will simply cause the shutdown promise to be delivered.
     (schema/validate a/TrapperkeeperAppContext @app-context)
     (schema/validate schema/Keyword svc-id)
     (assert (contains? (:service-contexts @app-context) svc-id))
     (schema/validate IFn f)
     (schema/validate (schema/maybe IFn) on-error-fn)

     (f)
     (catch Throwable t
       (log/error t (i18n/trs "shutdown-on-error triggered because of exception!"))
       (deliver shutdown-reason-promise {:cause       :service-error
                                         :error       t
                                         :on-error-fn (when on-error-fn
                                                        (partial on-error-fn (get @app-context svc-id)))})))))

(defprotocol ShutdownService
  (get-shutdown-reason [this])
  (wait-for-shutdown [this])
  (request-shutdown [this] [this opts]
    "Asynchronously triggers normal shutdown.  A specific exit status
    and final messages to display can be requested by associating an
    exit-request-schema compatible value with
    the :puppetlabs.trapperkeeper.core/exit key in the opts map.")
  (shutdown-on-error [this svc-id f] [this svc-id f on-error]
    "Higher-order function to execute application logic and trigger shutdown in
    the event of an exception"))

(schema/defn shutdown-service
  "Provides various functions for triggering application shutdown programatically.
  Primarily intended to serve application services, though TrapperKeeper also uses
  this service internally and therefore is always available in the application graph.

  Provides:
  * :get-shutdown-reason - Get a map containing the shutdown reason delivered
                           to the shutdown service.  If no shutdown reason has
                           been delivered, this function would return nil.
  * :wait-for-shutdown   - Block the calling thread until a shutdown reason
                           has been delivered to the shutdown service
  * :request-shutdown    - Asynchronously trigger normal shutdown
  * :shutdown-on-error   - Higher-order function to execute application logic
                           and trigger shutdown in the event of an exception

  For more information, see `request-shutdown` and `shutdown-on-error`."
  [shutdown-reason-promise :- IDeref
   app-context :- (schema/atom a/TrapperkeeperAppContext)]
  (s/service ShutdownService
    []
    (get-shutdown-reason [this] (when (realized? shutdown-reason-promise)
                                  @shutdown-reason-promise))
    (wait-for-shutdown [this] (deref shutdown-reason-promise))
    (request-shutdown [this]  (request-shutdown* shutdown-reason-promise))
    (request-shutdown [this opts]  (request-shutdown* shutdown-reason-promise opts))
    (shutdown-on-error [this svc-id f] (shutdown-on-error* shutdown-reason-promise app-context svc-id f))
    (shutdown-on-error [this svc-id f on-error] (shutdown-on-error* shutdown-reason-promise app-context svc-id f on-error))))

(schema/defn ^:always-validate shutdown! :- [Throwable]
  "Perform shutdown calling the `stop` lifecycle function on each service,
   in reverse order (to account for dependency relationships).
   Returns collection of exceptions thrown during shutdown sequence execution."
  [app-context :- (schema/atom a/TrapperkeeperAppContext)]
  (log/info (i18n/trs "Beginning shutdown sequence"))
  (let [{:keys [ordered-services shutdown-channel lifecycle-worker]} @app-context
        errors-chan (async/promise-chan)
        shutdown-fn (fn [] (let [results
                                 (doall
                                  (keep
                                   (fn [[service-id s]]
                                     (try
                                       (run-lifecycle-fn! app-context s/stop "stop" service-id s)
                                       nil
                                       (catch Exception e
                                         (log/error e (i18n/trs "Encountered error during shutdown sequence"))
                                         e)))
                                   (reverse ordered-services)))]
                             (async/put! errors-chan results)))]
    (log/trace (i18n/trs "Putting shutdown message on shutdown channel."))
    (async/>!! shutdown-channel {:type :shutdown
                                 :task-function shutdown-fn})
    ;; wait for the channel to send us the return value so we know it's done
    (log/trace (i18n/trs "Waiting for response to shutdown message from lifecycle worker."))
    (if (not (nil? (async/<!! lifecycle-worker)))
      (do
        (log/info (i18n/trs "Finished shutdown sequence"))
        ;; deliver errors from stopped services if any
        (async/<!! errors-chan))
      ;; else, the read from the channel returned a nil because it was closed,
      ;; indicating that there was already a shutdown in progress, and thus the
      ;; redundant shutdown request was ignored
      (log/trace (i18n/trs "Response from lifecycle worker indicates shutdown already in progress, ignoring additional shutdown attempt.")))))

(schema/defn ^:always-validate initialize-shutdown-service! :- (schema/protocol s/ServiceDefinition)
  "Initialize the shutdown service and add a shutdown hook to the JVM."
  [app-context :- (schema/atom a/TrapperkeeperAppContext)
   shutdown-reason-promise :- IDeref]
  (let [shutdown-service        (shutdown-service shutdown-reason-promise
                                                  app-context)]
    (ks/add-shutdown-hook! (fn []
                          (when-not (realized? shutdown-reason-promise)
                            (log/info (i18n/trs "Shutting down due to JVM shutdown hook."))
                            (shutdown! app-context)
                            (deliver shutdown-reason-promise {:cause :jvm-shutdown-hook}))))
    shutdown-service))

(defn get-app-shutdown-reason
  "Get a map containing the shutdown reason delivered to the shutdown service.
  If no shutdown reason has been delivered, this function would return nil.

  If non-nil, the reason map may contain the following keys:
  * :cause       - One of :requested, :service-error, or :jvm-shutdown-hook
  * :error       - The error associated with the :service-error cause
  * :on-error-fn - An optional error callback associated with the :service-error
                   cause"
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(or (nil? %) (map? %))]}
  (get-shutdown-reason (a/get-service app :ShutdownService)))

(defn throw-app-error-if-exists!
  "For the supplied app, attempt to pull out a shutdown reason error.  If
  one is available, throw a Throwable with that error.  If not, just return
  the app instance that was provided."
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(identical? app %)]}
  (when-let [shutdown-reason (get-app-shutdown-reason app)]
    (if-let [shutdown-error (:error shutdown-reason)]
      (throw shutdown-error)))
  app)

(defn wait-for-app-shutdown
  "Wait for shutdown to be initiated - either externally (such as Ctrl-C) or
  internally (requested by service or service error) - and return the reason.

  The reason map may contain the following keys:
  * :cause       - One of :requested, :service-error, or :jvm-shutdown-hook
  * :error       - The error associated with the :service-error cause
  * :on-error-fn - An optional error callback associated with the :service-error cause"
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(map? %)]}
  (wait-for-shutdown (a/get-service app :ShutdownService)))

(defn initiated-internally?
  "Given the shutdown reason obtained from `wait-for-shutdown`, determine whether
  the cause was internal so further shutdown steps can be performed.
  In the case of an externally-initiated shutdown (e.g. Ctrl-C), we assume that
  the JVM shutdown hook will perform the actual shutdown sequence as we don't want
  to rely on control returning to the main thread where it can perform shutdown."
  [shutdown-reason]
  {:pre  [(map? shutdown-reason)]
   :post [(ks/boolean? %)]}
  (contains? (disj shutdown-causes :jvm-shutdown-hook) (:cause shutdown-reason)))

(defn call-error-handler!
  "Given the shutdown reason obtained from `wait-for-shutdown`, call the
  error callback that was provided to `shutdown-on-error`, if there is one.
  An error will be logged if the function throws an exception."
  [shutdown-reason]
  {:pre [(map? shutdown-reason)]}
  (when-let [on-error-fn (:on-error-fn shutdown-reason)]
    (try
      (on-error-fn)
      (catch Throwable t
        (log/error t (i18n/trs "Error occurred during shutdown"))))))

;;;; end of shutdown-related functions

(schema/defn ^:always-validate build-app* :- (schema/protocol a/TrapperkeeperApp)
  "Given a list of services and a map of configuration data, build an instance
  of a TrapperkeeperApp.  Services are not yet initialized or started."
  [services :- [(schema/protocol s/ServiceDefinition)]
   config-data-fn :- IFn]
  (let [shutdown-reason-promise (promise)
        lifecycle-channel (async/chan max-pending-lifecycle-events)
        shutdown-channel (async/chan)
        ;; this is the application context for this app instance.  its keys
        ;; will be the service ids, and values will be maps that represent the
        ;; context for each individual service
        app-context (atom {:service-contexts {}
                           :ordered-services []
                           :services-by-id {}
                           :lifecycle-channel lifecycle-channel
                           :shutdown-channel shutdown-channel
                           :lifecycle-worker (initialize-lifecycle-worker
                                              lifecycle-channel
                                              shutdown-channel
                                              shutdown-reason-promise)
                           :shutdown-reason-promise shutdown-reason-promise})
        service-refs (atom {})
        services (conj services
                       (config-service config-data-fn)
                       (initialize-shutdown-service! app-context
                                                     shutdown-reason-promise))
        service-map (apply merge (map s/service-map services))
        compiled-graph (compile-graph service-map)
        ;; this gives us an ordered graph that we can use to call lifecycle
        ;; functions in the correct order later
        graph (graph/->graph service-map)
        ;; when we instantiate the graph, we pass in the context atom.
        graph-instance (instantiate compiled-graph {:tk-app-context app-context
                                                    :tk-service-refs service-refs})
        ;; dereference the atom of service references, since we don't need to update it
        ;; any further
        services-by-id @service-refs
        ordered-services (map (fn [[service-id _]] [service-id (services-by-id service-id)]) graph)]
    (swap! app-context assoc
           :services-by-id services-by-id
           :ordered-services ordered-services)
    (doseq [svc-id (keys services-by-id)] (swap! app-context assoc-in [:service-contexts svc-id] {}))
    ;; finally, create the app instance
    (reify
      a/TrapperkeeperApp
      (a/get-service [this protocol] (services-by-id (keyword protocol)))
      (a/service-graph [this] graph-instance)
      (a/app-context [this] app-context)
      (a/check-for-errors! [this] (throw-app-error-if-exists!
                                   this))
      (a/init [this]
        (run-lifecycle-fns app-context s/init "init" ordered-services)
        this)
      (a/start [this]
        (run-lifecycle-fns app-context s/start "start" ordered-services)
        (inc-restart-counter! this)
        this)
      (a/stop [this]
        (a/stop this false))
      (a/stop [this throw?]
        (let [errors (shutdown! app-context)]
          (if (and throw? (seq errors))
            (let [msg (i18n/trs "Error during app shutdown!")]
              (log/error msg)
              (throw (ex-info msg {:errors errors})))
            this)))
      (a/restart [this]
        (try
          (run-lifecycle-fns app-context s/stop "stop" (reverse ordered-services))
          (doseq [svc-id (keys services-by-id)] (swap! app-context assoc-in [:service-contexts svc-id] {}))
          (run-lifecycle-fns app-context s/init "init" ordered-services)
          (run-lifecycle-fns app-context s/start "start" ordered-services)
          (inc-restart-counter! this)
          this
          (catch Throwable t
            (deliver shutdown-reason-promise {:cause :service-error
                                              :error t})))))))

(schema/defn ^:always-validate boot-services-for-app**
  "Boots services for a TK app.  WARNING:  This should only ever be called
  on the lifecycle-worker, presumably via `boot-services-for-app*`"
  [result-promise :- IDeref
   app :- (schema/protocol a/TrapperkeeperApp)]
  (let [{:keys [shutdown-reason-promise]} @(a/app-context app)]
    (try
      (a/init app)
      (a/start app)
      (catch Throwable t
        (deliver shutdown-reason-promise {:cause :service-error
                                          :error t})))
    (deliver result-promise app)))

(schema/defn ^:always-validate boot-services-for-app* :- (schema/protocol a/TrapperkeeperApp)
  [app :- (schema/protocol a/TrapperkeeperApp)]
  (let [lifecycle-channel (:lifecycle-channel @(a/app-context app))
        lifecycle-promise (promise)
        boot-fn (partial boot-services-for-app** lifecycle-promise app)]
    (async/>!! lifecycle-channel {:type :boot
                                  :task-function boot-fn})
    @lifecycle-promise
    app))

(schema/defn ^:always-validate boot-services* :- (schema/protocol a/TrapperkeeperApp)
  "Given the services to run and the map of configuration data, create the
  TrapperkeeperApp and boot the services.  Returns the TrapperkeeperApp."
  [services :- [(schema/protocol s/ServiceDefinition)]
   config-data-fn :- IFn]
  (let [app                     (try
                                  (build-app* services
                                              config-data-fn)
                                  (catch Throwable t
                                    (log/error t (i18n/trs "Error during app buildup!"))
                                    (throw t)))]
    (boot-services-for-app* app)
    app))


