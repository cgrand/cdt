(ns com.georgejahad.cdt
  (:require [clojure.contrib.str-utils2 :as str2])
  (:use [clojure.contrib.seq-utils :only [indexed]])
  (:import java.util.ArrayList))

;; This handles the fact that tools.jar is a global dependency that
;; can't really be in a repo:
(with-out-str (add-classpath (format "file://%s/../lib/tools.jar"
                                     (System/getProperty "java.home"))))
(import com.sun.jdi.Bootstrap
        com.sun.jdi.request.EventRequest
        com.sun.jdi.event.BreakpointEvent
        com.sun.jdi.event.ExceptionEvent
        com.sun.jdi.event.LocatableEvent)

(use 'alex-and-georges.debug-repl)
(defn regex-filter [regex seq]
  (filter #(re-find regex (.name %)) seq))

(def conn
     (memoize
      (fn [] (first (regex-filter #"SocketAttach"
                                  (.allConnectors
                                   (Bootstrap/virtualMachineManager)))))))

(defonce vm-data (atom nil))

(defn vm [] @vm-data)

(defn cont []
  (.resume (vm)))

(defn list-threads []
  (.allThreads (vm)))

(defonce current-thread (atom nil))

(defn set-current-thread [thread-num]
  (reset! current-thread (nth (list-threads) thread-num)))

(def sct set-current-thread)

(defn ct [] @current-thread)

(defonce current-frame (atom 0))

(defn set-current-frame [frame]
  (reset! current-frame frame))

(def scf set-current-frame)

(defn cf [] @current-frame)

(defn up []
  (let [max (dec (count (.frames (ct))))]
    (if (< (cf) max)
      (scf (inc (cf)))
      (println "already at top of stack"))))

(defn down []
  (if (> (cf) 0)
    (scf (dec (cf)))
    (println "already at bottom of stack")))

(defn handle-event [e]
  (Thread/yield)
  (condp #(instance? %1 %2) e
    BreakpointEvent (println "\n\nBreakpoint" e "hit\n\n")
    ExceptionEvent (println "\n\nException" e
                            (.catchLocation e) "hit\n\n")
    :default (println "other event hit")))

(defn get-thread [#^LocatableEvent e]
  (.thread e))

(defn finish-set [s] 
  (let [e (first (iterator-seq (.eventIterator s)))]
    (set-current-frame 0)
    (reset! current-thread (get-thread e))))

(defn handle-events []
  (println "starting event handler")
  (let [q (.eventQueue (vm))]
    (while true
           (let [s (.remove q)]
             (doseq [i (iterator-seq (.eventIterator s))]
               (handle-event i))
             (finish-set s)))))

(def event-handler (atom nil))

(defn cdt-attach [port]
  (let [args (.defaultArguments (conn))]
    (.setValue (.get args "port") port)
    (reset! vm-data (.attach (conn) args))
    (reset! event-handler (Thread. handle-events))
    (.start @event-handler)))

(defn find-classes [class-regex]
  (regex-filter class-regex (.allClasses (vm))))

(defn find-methods [class method-regex]
  (regex-filter method-regex (.methods class)))

(def rt (memoize (fn [] (first (find-classes #"clojure.lang.RT")))))

(def co (memoize (fn [] (first (find-classes #"clojure.lang.Compiler")))))

(def va (memoize (fn [] (first (find-classes #"clojure.lang.Var")))))

(def rstring (memoize (fn [] (first (find-methods (rt) #"readString")))))

(def as (memoize (fn [] (first (find-methods (rt) #"assoc")))))

(def ev (memoize (fn [] (first (find-methods (co) #"eval")))))

(def ge (memoize (fn [] (first (find-methods (va) #"get")))))

(def sroot (memoize (fn [] (first (find-methods (va) #"swapRoot")))))

(defn print-threads []
  (doseq [[n t] (indexed (seq (list-threads)))]
    (println n (.name t))))

(defrecord BpSpec [sym methods bps])

(defonce bp-list (atom {}))

(defn merge-with-exception [short-name]
  (partial merge-with
           #(throw (IllegalArgumentException.
                    (str "bp-list already contains a " short-name)))))
(defn create-bp [m]
  (doto (.createBreakpointRequest
         (.eventRequestManager (vm)) (.location m))
    (.setSuspendPolicy EventRequest/SUSPEND_EVENT_THREAD)
    (.setEnabled true)))

(defn gen-class-pattern [sym]
  (let [s (str2/replace (str sym) "/" "\\$")]
    (re-pattern (str "^" s "$"))))

(defn get-methods [sym]
  (for [c (find-classes (gen-class-pattern sym))
        m (regex-filter #"(invoke|doInvoke)" (.methods c))] m))

(defn set-bp-fn [sym short-name]
  (let [methods (get-methods sym)
        k (keyword short-name)
        bps (map create-bp methods)]
    (swap! bp-list (merge-with-exception k) {k (BpSpec. sym methods bps)})
    (println "bp set on" k)))

(defmacro set-bp
  ([sym]
     (let [short-name (symbol (second (seq (.split (str sym) "/"))))]
       `(set-bp ~sym ~short-name)))
  ([sym short-name]
     `(set-bp-fn '~sym '~short-name)))

(defn delete-bp [short-name]
  (doseq [bp (:bps (short-name @bp-list))]
    (.setEnabled bp false)
    (.deleteEventRequest (.eventRequestManager (vm)) bp))
  (swap! bp-list dissoc short-name))

(defonce catch-list (atom {}))

(defn set-catch [class type]
  (let [caught (boolean (#{:all :caught} type))
        uncaught (boolean (#{:all :uncaught} type))
        pattern (re-pattern (second (.split (str class) " " )))
        ref-type (first (find-classes pattern))
        catch-request
        (doto (.createExceptionRequest (.eventRequestManager (vm))
                                       ref-type caught uncaught)
          (.setEnabled true))]
    (swap! catch-list assoc class catch-request)
    (println "catch set on" class)))

(defn delete-catch [class]
  (let [catch-request (@catch-list class)]
    (.setEnabled catch-request false)
    (.deleteEventRequest (.eventRequestManager (vm)) catch-request)
    (swap! catch-list dissoc class)))

(defn remote-create-str [form]
  (.mirrorOf (vm) (str form)))

(defn make-arg-list [ & args]
  (ArrayList. (or args [])))

(defn remote-invoke [class-fn method-fn arglist thread frame]
  (.invokeMethod (class-fn) thread (method-fn) arglist frame))

(def remote-eval (partial remote-invoke co ev))

(def remote-read-string (partial remote-invoke rt rstring))

(def remote-assoc (partial remote-invoke rt as))

(defn remote-get [v]
  (remote-invoke (constantly v) ge (make-arg-list) (ct) (cf)))

(defn remote-swap-root [v arg-list]
  (remote-invoke (constantly v) sroot arg-list (ct) (cf)))

(declare  reval-ret* reval-ret-str reval-ret-obj)

(defn convert-type [type val]
  (reval-ret-obj (list 'new type (str val)) false))

(defn gen-conversion [t]
  (let [c (Class/forName (str "com.sun.tools.jdi." t "ValueImpl"))
        ctor (if (= t 'Char) 'Character t)]
    [c (partial convert-type ctor)]))

(defmacro gen-conversion-map [types]
  `(into {} (map gen-conversion '~types)))

(def conversion-map (gen-conversion-map [Boolean Integer Byte Char Double Float Integer Long Short]))

(defn add-local-to-map [m l]
  (let [val (if-let [f (conversion-map (type (val l)))]
              (f (val l))
              (val l))]
    (remote-assoc
     (make-arg-list m
                    (remote-create-str (.name (key l))) val) (ct) (cf))))

(def cdt-sym (atom nil))

(defn get-cdt-sym []
  (or @cdt-sym
      (reset! cdt-sym
              (symbol (read-string
                       (str (reval-ret-str `(gensym "cdt-") false)))))))

(defn add-locals-to-map []
  (let [frame (.frame (ct) (cf))
        locals (.getValues frame (.visibleVariables frame))
        sym (get-cdt-sym)
        v (reval-ret-obj `(intern '~'user '~sym {}) false)
        new-map (reduce add-local-to-map (remote-get v) locals)]
    (remote-swap-root v (make-arg-list new-map))
    locals))

(defn gen-local-bindings [sym locals]
  (into [] (mapcat
            (fn [l]
              (let [local-name (.name (key l))]
                `[~(symbol local-name)
                  ((var-get (ns-resolve '~'user '~sym)) ~local-name)]))
            locals)))

(defn gen-form-with-locals [form]
  (let [locals (add-locals-to-map)]
    `(let ~(gen-local-bindings (get-cdt-sym) locals) ~form)))

(defn gen-form [form return-str?]
  (let [form (if return-str?
               `(with-out-str (pr (eval '~form)))
               `(eval '~form))]
    `(try ~form
          (catch Throwable t#
            (with-out-str (pr (str "remote exception: " t#)))))))

(defn reval-ret*
  [return-str? form locals?]
  (let [form (if-not locals? form
                     (gen-form-with-locals form))]
    (-> (remote-create-str (gen-form form return-str?))
        make-arg-list
        (remote-read-string (ct) (cf))
        make-arg-list
        (remote-eval (ct) (cf)))))

(def reval-ret-str (partial reval-ret* true))
(def reval-ret-obj (partial reval-ret* false))

(defn fixup-string-reference-impl [sri]
  ;; remove the extra quotes caused by the stringReferenceImpl
  (apply str (butlast (drop 1 (seq (str sri))))))

(defn local-names 
  ([] (local-names (cf)))
  ([f]
     (let [frame (.frame (ct) f)
           locals (.getValues frame (.visibleVariables frame))]
       (into [] (map #(symbol (.name %)) (keys locals))))))

(defn locals [] 
  (dorun (map #(println %1 %2)
              (local-names)
              (read-string (fixup-string-reference-impl
                            (reval-ret-str (local-names) true))))))
(defn print-frames
  ([] (print-frames (ct)))
  ([thread]
     (doseq [[i f] (indexed (.frames thread))]
       (let [l (.location f)
             ln (try (str (local-names i)) (catch Exception e "[]"))
             sp (try (.sourcePath l) (catch Exception e "source not found"))
             sp (last  (.split sp "/"))
             c (.name (.declaringType (.method l)))]
         
         (printf "%3d %s %s %s %s:%d\n" i c (.name (.method l))
                 ln sp (.lineNumber l))))))
(defmacro reval
  ([form]
     `(reval ~form true))
  ([form locals?]
     `(read-string (fixup-string-reference-impl
                    (reval-ret-str '~form ~locals?)))))

(defmacro reval-println
  ([form]
     `(reval-println ~form true))
  ([form locals?]
     `(println (str (reval-ret-str '~form ~locals?)))))
