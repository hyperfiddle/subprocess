(ns rksm.subprocess
  (:require [clojure.core.async
             :as async
             :refer [go go-loop chan <! >! <!! >!! alt!! timeout close! put!]]
            [clojure.java.io :as io]))

(defn capture-stream
  [stream & [verbose?]]
  (let [reader (io/reader stream)
        read-chan (chan)]
    (go-loop []
      (if-let [line (.readLine reader)]
        (do
          (put! read-chan line)
          (when verbose?
            (binding [*out* (java.io.PrintWriter. System/out)]
              (println line)
              (.flush *out*)))
          (recur))
        (close! read-chan)))
    read-chan))

(defn- ^"[Ljava.lang.String;" as-env-strings
  "Stolen from clojure.java.shell
  Helper so that callers can pass a Clojure map for the :env to sh."
  [arg]
  (cond
   (nil? arg) nil
   (map? arg) (into-array String (map (fn [[k v]] (str (name k) "=" v)) arg))
   true arg))

(defn async-proc
  "Function to start a shell process. Pass the command and then optionally
  arguments and options along, like:
  (rksm.subprocess/async-proc \"ls\" \"-l\" \"-a\" :verbose? true)
  options are: verbose?, env"
  [& cmd+args+opts]
  (let [[cmd+args opts] (split-with string? cmd+args+opts)
        opts (apply hash-map opts)
        verbose? (:verbose? opts)
        env (as-env-strings (:env opts))
        dir (some-> (:dir opts) (java.io.File.))
        proc (.. Runtime getRuntime (exec (into-array String cmd+args) env dir))
        out (capture-stream (.getInputStream proc) verbose?)
        err (capture-stream (.getErrorStream proc) verbose?)
        proc-state (atom {:out out :err err :proc proc :exited? false})]
    (future (.waitFor proc) (swap! proc-state assoc :exited? true))
    proc-state))

(defn process-obj [proc]
  (:proc @proc))

(defn- get-field
  [proc field-name]
  (let [p (process-obj proc)]
    (if-let [pid-field (.getDeclaredField (class p) (name field-name))]
      (-> pid-field
        (doto (.setAccessible true))
        (.get p)))))

(defn pid
  "seriously? Java has no way of official getting a pid of a process???"
  [proc]
  (get-field proc "pid"))

(defn wait-for
  [proc]
  (.waitFor (process-obj proc)))

(defn exited? [proc] (:exited? @proc))

(defn exit-code [proc] (get-field proc "exitcode"))

(defn- read-chan [proc chan-name]
  (loop [result ""]
    (if-let [val (<!! (chan-name @proc))]
      (recur (str result "\n" val))
      result)))

(defn stdout [proc]
  (read-chan proc :out))

(defn stderr [proc]
  (read-chan proc :err))

(defn signal
  ([proc] (signal proc "KILL"))
  ([proc sig]
   (if (exited? proc)
     (exit-code proc)
     (let [kill-proc (.exec (java.lang.Runtime/getRuntime)
                            (format "kill -s %s %s" sig (pid proc)))
           exit-chan (chan)]
       (async/go
        (.waitFor (process-obj proc))
        (>! exit-chan (or (exit-code proc) :exited)))
       (alt!!
        (timeout 100) :timeout
        exit-chan ([code] code))))))

(comment

  (def p (async-proc "ls"))
  (def chan-name :out)
  (async/<!!(chan-name @p))

  (stdout p)
  (stderr p)
  123
  p
  (async-proc "echo" "123")

  (let [proc (async-proc "bash" "-c" "echo 1; sleep .3; echo 2; sleep .3; echo 3")]
    (future
      (while (not (:exited? @proc))
        (println "Got output" (async/<!! (:out @proc))))
      (println "process exited")))
  )
