;;; lazytest.clj

;; by Stuart Sierra, http://stuartsierra.com/

;; Copyright (c) 2010 Stuart Sierra. All rights reserved.  The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0, which can be found at
;; http://opensource.org/licenses/eclipse-1.0.php
;;
;; By using this software in any fashion, you are agreeing to be bound
;; by the terms of this license.  You must not remove this notice, or
;; any other, from this software.


;;; PROTOCOLS

(defprotocol TestSuccess
  (success? [r] "Returns true if r is a 100% successful result."))

(defprotocol Testable
  (run-tests [x] "Runs tests defined for the Namespace, Var, or TestCase."))


;;; DATATYPES

(deftype Context [parents before after])

(deftype TestCase [contexts children])

(deftype Assertion [locals form])

(deftype TestResult [source children]
  TestSuccess (success? [] (every? success? children)))
 
(deftype TestThrown [source error]
  TestSuccess (success? [] false))
 
(deftype AssertionPassed [source]
  TestSuccess (success? [] true))
 
(deftype AssertionFailed [source]
  TestSuccess (success? [] false))
 
(deftype AssertionThrown [source error]
  TestSuccess (success? [] false))


;;; ASSERTION HANDLING

(defn- format-assertion [a]
  {:pre [(= ::Assertion (type a))
         (vector? (:locals a))]}
  (let [form (:form a)]
    `(fn ~(:locals a)
       (try (if ~form
              (AssertionPassed '~form)
              (AssertionFailed '~form))
            (catch Throwable t#
              (AssertionThrown '~form t#))))))
 
(defn compile-assertion [a]
  (eval (format-assertion a)))
 
(defmacro assertion [locals & body]
  (format-assertion (Assertion locals `(do ~@body))))


;;; CONTEXT HANDLING

(defn open-context
  "Opens context c, and all its parents, unless it is already active."
  [active c]
  (let [active (reduce open-context active (:parents c))
        states (map active (:parents c))]
    (if-let [f (:before c)]
      (assoc active c (or (active c) (apply f states)))
      active)))

(defn close-context
  "Closes context c and removes it from active."
  [active c]
  (let [states (map active (:parents c))]
    (when-let [f (:after c)]
      (apply f (active c) states))
    (let [active (reduce close-context active (:parents c))]
      (dissoc active c))))


;;; TEST CASE HANDLING

(defn- unchunked-map
  "Like map but does not chunk results; slower but lazier than map."
  [f coll]
  (lazy-seq
   (when (seq coll)
     (cons (f (first coll))
           (unchunked-map f (next coll))))))

(defn- has-after?
  "True if Context c or any of its parents has an :after function."
  [c]
  (or (:after c)
      (some has-after? (:parents c))))

(defn run-test-case
  "Executes a test case in context."
  ([t] (run-test-case t {}))
  ([t active]
     {:pre [(= ::TestCase (type t))
            (every? #(= ::Context (type %)) (:contexts t))]}
     (try
      (let [merged (reduce open-context active (:contexts t))
            states (map merged (:contexts t))
            ;; Prevent chunking for truly lazy execution:
            results (unchunked-map (fn [c] (if (fn? c)
                                             (apply c states)
                                             (run-test-case c merged)))
                                   (:children t))]
        ;; Force non-lazy execution to handle shutdown properly:
        (when (some has-after? (:contexts t))
          (dorun results)
          (dorun (reduce close-context merged
                         ;; Only close contexts that weren't active at start:
                         (filter #(not (contains? active %))
                                 (reverse (:contexts t))))))
        (TestResult t results))
      (catch Throwable e (TestThrown t e)))))
 

;;; TEST RESULT HANDLING

(defn result-seq
  "Given a single TestResult, returns a depth-first sequence of that
  TestResult and all its children."
  [r]
  (tree-seq :children :children r))


;;; TESTABLE IMPLEMENTATIONS

(defn var-test-case
  "Creates a TestCase for a Var, using clojure.core/test."
  [v]
   #^{:name (:name (meta v))}
   (TestCase [] [(assertion [] (clojure.core/test v))]))
 
(defn test-var
  "Creates and runs a TestCase for the Var."
  [v]
  (run-test-case (var-test-case v)))
 
(defn ns-test-case
  "Creates a TestCase for the namespace that tests all its Vars."
  [n]
  #^{:name (ns-name n)}
  (TestCase [] (map var-test-case (ns-interns n))))
 
(defn test-ns
  "Creates and runs a TestCase for the namespace."
  [n]
  (run-test-case (ns-test-case n)))
 
(extend ::TestCase Testable {:run-tests run-test-case})
(extend clojure.lang.Var Testable {:run-tests test-var})
(extend clojure.lang.Namespace Testable {:run-tests test-ns})



;;; SELF-TESTS

;; Assertions
(def a1 (assertion [a] (pos? a)))

(assert (= ::AssertionPassed (type (a1 1))))
(assert (= ::AssertionFailed (type (a1 -1))))
(assert (= ::AssertionThrown (type (a1 "string"))))

;; Contexts
(def c1 (Context nil nil nil))

(assert (= {} (open-context {} c1)))
(assert (= {} (close-context {} c1)))

(def c2 (Context nil (fn [] 2) (fn [x] (assert (= x 2)))))

(assert (= {c2 2} (open-context {} c2)))
(assert (= {} (close-context {c2 2} c2)))
(assert (= {c2 0} (open-context {c2 0} c2)))

(def c3 (Context [c2] (fn [s2] (assert (= s2 2)) 3)
                 (fn [x s2] (assert (= x 3)) (assert (= s2 2)))))

(assert (= {c2 2, c3 3} (open-context {} c3)))
(assert (= {} (close-context {c2 2, c3 3} c3)))

;; Simple TestCase
(def t1 (TestCase [c2] [a1 a1 a1]))

(assert (= ::TestResult (type (run-test-case t1))))
(assert (every? #(= ::AssertionPassed (type %))
                (:children (run-test-case t1))))

;; TestCase with nested Context
(def c4 (Context [c2] (fn [s2] (assert (= s2 2)) 4)
                 (fn [s4 s2] (assert (= s4 4)) (assert (= s2 2)))))

(def t2 (TestCase [c4] [a1]))

(assert (= ::AssertionPassed
           (type (first (:children (run-test-case t2))))))

;; TestCase with multiple Contexts
(def a2 (assertion [x y] (< x y)))

(def t3 (TestCase [c2 c4] [a2 a2]))

(assert (every? #(= ::AssertionPassed (type %))
                (:children (run-test-case t3))))

;; Context Ordering
(declare *log*)

(defn log [event]
  (swap! *log* conj event))

(defmacro with-log [& body]
  `(binding [*log* (atom [])]
     ~@body))

(def a3 (assertion [x] (log :a3) (pos? x)))

(def a4 (assertion [x] (log :a4) (pos? x)))

(def c5 (Context [] (fn [] (log :c5-open) 5)
                 (fn [x] (assert (= x 5)) (log :c5-close))))

(def t4 (TestCase [c5] [a3 a4]))

(with-log
  (run-test-case t4)
  (assert (= @*log* [:c5-open :a3 :a4 :c5-close])))

;; Lazy Evaluation
(def c6 (Context [] (fn [] (log :c6-open) 6) nil))

(def t5 (TestCase [c6] [a3 a4]))

(with-log
  (let [results (run-test-case t5)]
    (assert (= @*log* [:c6-open]))
    (dorun (result-seq results))
    (assert (= @*log* [:c6-open :a3 :a4]))))

;; Nested Lazy Evaluation
(def t6 (TestCase [] [t5 t5]))

(with-log
  (let [results (run-test-case t6)]
    (assert (= @*log* []))
    (dorun (:children (first (:children results))))
    (assert (= @*log* [:c6-open :a3 :a4]))
    (dorun (result-seq results))
    (assert (= @*log* [:c6-open :a3 :a4 :c6-open :a3 :a4]))))

(def t7 (TestCase [c6] [t5 t5]))

(with-log
  (let [results (run-test-case t7)]
    (assert (= @*log* [:c6-open]))
    (dorun (result-seq results))
    (assert (= @*log* [:c6-open :a3 :a4 :a3 :a4]))))
