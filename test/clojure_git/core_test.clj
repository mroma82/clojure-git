(ns clojure-git.core-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure-git.core :as git]))

;; ---------------------------------------------------------------------------
;; Fixture: build a small throwaway repository
;; ---------------------------------------------------------------------------

(def ^:dynamic *repo* nil)
(def ^:private clock (atom 1700000000))

(defn- sh! [dir & args]
  (let [t   (swap! clock + 60)
        env (merge (into {} (System/getenv))
                   {"GIT_AUTHOR_NAME" "Ada" "GIT_AUTHOR_EMAIL" "ada@example.com"
                    "GIT_COMMITTER_NAME" "Ada" "GIT_COMMITTER_EMAIL" "ada@example.com"
                    "GIT_AUTHOR_DATE" (str t " +0000") "GIT_COMMITTER_DATE" (str t " +0000")
                    "GIT_CONFIG_NOSYSTEM" "1" "HOME" dir})
        {:keys [exit out err]} (apply shell/sh (concat args [:dir dir :env env]))]
    (when-not (zero? exit) (throw (ex-info err {:args args})))
    out))

(defn- write! [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)))

(defn- build-repo []
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "clojure-git-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        g   (partial sh! dir "git")]
    (g "init" "-q" "-b" "main")
    ;; c1 (root)
    (write! dir "a.txt" "one\ntwo\n")
    (write! dir "b.txt" "bee\n")
    (g "add" ".") (g "commit" "-q" "-m" "Initial commit" "-m" "With a body.")
    (g "tag" "-a" "v1.0.0" "-m" "First release")
    ;; c2
    (write! dir "a.txt" "one\ntwo\nthree\n")
    (g "rm" "-q" "b.txt")
    (g "commit" "-q" "-am" "Edit a, remove b")
    ;; c3 rename + binary
    (io/make-parents (io/file dir "src/x"))
    (g "mv" "a.txt" "src/a.txt")
    (with-open [o (io/output-stream (io/file dir "logo.bin"))]
      (.write o (byte-array [0 1 2 0 3 4 0])))
    (g "add" ".") (g "commit" "-q" "-m" "Move a into src, add logo")
    (g "tag" "v1.1.0")
    ;; feature branch
    (g "checkout" "-q" "-b" "feature")
    (write! dir "feature.txt" "feat\n")
    (g "add" ".") (g "commit" "-q" "-m" "Add feature")
    ;; back on main
    (g "checkout" "-q" "main")
    (write! dir "src/a.txt" "one\ntwo\nthree\nfour\n")
    (g "commit" "-q" "-am" "Extend a")
    (g "tag" "-a" "v2.0.0" "-m" "Second release")
    (g "merge" "-q" "--no-ff" "feature" "-m" "Merge feature")
    dir))

(defn- delete-tree [f]
  (doseq [c (reverse (file-seq (io/file f)))] (io/delete-file c true)))

(use-fixtures :once
  (fn [t]
    (let [dir (build-repo)]
      (try (binding [*repo* (git/connect dir)] (t))
           (finally (delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest connect-test
  (is (git/repo? *repo*))
  (is (= *repo* (git/connect (str (:path *repo*) "/src"))) "sub-directory resolves to repo root")
  (is (thrown? clojure.lang.ExceptionInfo (git/connect "/definitely/not/here")))
  (is (thrown? clojure.lang.ExceptionInfo (git/commits {:path "."})) "plain map is not a connection"))

(deftest refs-test
  (is (= "main" (git/current-branch *repo*)))
  (is (= 40 (count (git/head *repo*))))
  (is (nil? (git/resolve-ref *repo* "nope")))
  (is (git/ancestor? *repo* "v1.0.0" "v2.0.0"))
  (is (not (git/ancestor? *repo* "v2.0.0" "v1.0.0")))
  (is (= (git/resolve-ref *repo* "v1.1.0") (git/merge-base *repo* "main~1" "feature")))
  (is (thrown? clojure.lang.ExceptionInfo (git/resolve-ref *repo* "--all")) "option injection rejected"))

(deftest commits-test
  (let [all (git/commits *repo*)]
    (is (= 6 (count all)))
    (is (= "Merge feature" (:subject (first all))))
    (is (:merge? (first all)))
    (is (= "Initial commit" (:subject (last all))))
    (is (= "With a body." (:body (last all))))
    (is (= "Initial commit\n\nWith a body." (:message (last all))))
    (is (= {:name "Ada" :email "ada@example.com"} (select-keys (:author (last all)) [:name :email])))
    (is (inst? (get-in (last all) [:author :date])))
    (is (= ["v1.0.0"] (get-in (last all) [:refs :tags]))))

  (testing "ranges between tags"
    (is (= ["Merge feature" "Extend a" "Add feature"]
           (map :subject (git/commits *repo* {:from "v1.1.0"}))))
    (is (= ["Extend a" "Move a into src, add logo" "Edit a, remove b"]
           (map :subject (git/commits-between *repo* "v1.0.0" "v2.0.0"))))
    (is (= ["Extend a" "Move a into src, add logo" "Edit a, remove b" "Initial commit"]
           (map :subject (git/commits *repo* {:from "v1.0.0" :to "v2.0.0" :inclusive true}))))
    (is (= ["Add feature"] (map :subject (git/commits *repo* {:rev "main~1..feature"})))))

  (testing "filters"
    (is (= 2 (count (git/commits *repo* {:limit 2}))))
    (is (= ["Add feature"] (map :subject (git/commits *repo* {:path "feature.txt"}))))
    (is (= 5 (count (git/commits *repo* {:no-merges true}))))
    (is (= "Initial commit" (:subject (first (git/commits *repo* {:reverse true})))))
    (is (= 4 (git/count-commits *repo* {:first-parent true :from "v1.0.0"})))
    (is (= 6 (git/count-commits *repo*))))

  (testing ":files option"
    (let [c (first (git/commits *repo* {:from "v1.1.0" :to "feature" :files true}))]
      (is (= [{:path "feature.txt" :status :added :additions 1 :deletions 0 :binary? false}]
             (:files c))))))

(deftest single-commit-test
  (let [c (git/commit *repo* "v1.1.0")]
    (is (= "Move a into src, add logo" (:subject c)))
    (is (= #{"src/a.txt" "logo.bin"} (set (map :path (:files c)))))
    (let [{:keys [status old-path similarity]} (first (filter #(= "src/a.txt" (:path %)) (:files c)))]
      (is (= :renamed status))
      (is (= "a.txt" old-path))
      (is (= 100 similarity)))
    (is (:binary? (first (filter #(= "logo.bin" (:path %)) (:files c))))))
  (testing "root commit lists every file"
    (is (= #{"a.txt" "b.txt"} (set (map :path (:files (git/commit *repo* "v1.0.0")))))))
  (testing "merge commit diffs against first parent"
    (is (= ["feature.txt"] (map :path (:files (git/commit *repo* "HEAD"))))))
  (is (nil? (git/commit *repo* "does-not-exist")))
  (is (not (contains? (git/commit *repo* "HEAD" {:files false}) :files))))

(deftest changed-files-test
  (is (= [{:path "a.txt" :status :modified :additions 1 :deletions 0 :binary? false}
          {:path "b.txt" :status :deleted :additions 0 :deletions 1 :binary? false}]
         (git/changed-files *repo* "HEAD~3")                ; single commit
         (git/changed-files *repo* "v1.0.0" "HEAD~3")))     ; range
  (let [r (git/changed-files *repo* "v1.0.0" "v2.0.0")]
    ;; a.txt changed too much to count as a rename across this range
    (is (= #{"a.txt" "b.txt" "src/a.txt" "logo.bin"} (set (map :path r))))
    (is (= :deleted (:status (first (filter #(= "a.txt" (:path %)) r))))))
  (is (= ["src/a.txt"] (map :path (git/changed-files *repo* "v1.0.0" "v2.0.0" {:path "src"}))))
  (is (= ["feature.txt"] (map :path (git/changed-files *repo* "main~1" "feature" {:merge-base true})))))

(deftest tags-test
  (let [ts (git/tags *repo*)]
    (is (= ["v1.0.0" "v1.1.0" "v2.0.0"] (map :name ts)))
    (is (= [true false true] (map :annotated? ts)))
    (is (= "First release" (:message (first ts))))
    (is (= "Ada" (get-in (first ts) [:tagger :name])))
    (is (= "ada@example.com" (get-in (first ts) [:tagger :email])))
    (is (= (git/resolve-ref *repo* "v1.0.0") (:sha (first ts))) ":sha is the commit, not the tag object"))
  (is (= ["v2.0.0" "v1.1.0" "v1.0.0"] (map :name (git/tags *repo* {:sort "-creatordate"}))))
  (is (= ["v1.1.0" "v2.0.0"] (map :name (git/tags-between *repo* "v1.0.0" "HEAD"))))
  (is (= ["v1.0.0" "v1.1.0"] (map :name (git/tags *repo* {:pattern "v1.*"}))))
  (is (= ["v1.1.0" "v2.0.0"] (map :name (git/tags *repo* {:contains "v1.1.0"}))))
  (is (= "v2.0.0" (git/latest-tag *repo*)))
  (is (= "v1.1.0" (git/latest-tag *repo* "feature")))
  (is (= "v1.1.0" (:name (git/tag *repo* "v1.1.0"))))
  (is (nil? (git/tag *repo* "v9"))))

(deftest branches-test
  (let [bs (git/branches *repo*)]
    (is (= ["feature" "main"] (map :name bs)))
    (is (= [false true] (map :current? bs)))
    (is (= "Merge feature" (:subject (second bs)))))
  (is (= ["feature" "main"] (map :name (git/branches *repo* {:merged "main"}))))
  (is (= [] (git/branches *repo* {:type :remote}))))

(deftest log-test
  (let [[e] (git/log *repo* {:from "v1.1.0" :to "feature"})]
    (is (= "Add feature" (:subject e)))
    (is (= {:files 1 :additions 1 :deletions 0} (:stats e)))
    (is (= ["feature.txt"] (map :path (:files e)))))
  (is (= 6 (count (git/log *repo*))))
  (let [e (first (git/log *repo* {:limit 1 :files false}))]
    (is (not (contains? e :files)))
    (is (not (contains? e :stats)))))

(deftest text-output-test
  (is (str/includes? (git/log-text *repo* {:format :oneline :limit 1}) "Merge feature"))
  (is (= "Extend a\nMove a into src, add logo\n"
         (git/log-text *repo* {:format "%s" :from "v1.0.0" :to "v2.0.0" :limit 2})))
  (is (str/includes? (git/diff *repo* "v2.0.0") "+four"))
  (is (str/includes? (git/diff *repo* "v1.0.0" "v2.0.0") "deleted file mode"))
  (is (= "one\ntwo\n" (git/file-at *repo* "v1.0.0" "a.txt")))
  (is (nil? (git/file-at *repo* "v1.0.0" "src/a.txt")))
  (is (= [{:name "Ada" :email "ada@example.com" :commits 6}] (git/contributors *repo*)))
  (is (= [] (git/remotes *repo*))))
