(ns clojure-git.core
  "Read-only access to git data in a local repository.

  Start with `connect`, which returns a `Repo` connection, then pass that
  connection as the first argument to every other function:

    (require '[clojure-git.core :as git])
    (def repo (git/connect \"/path/to/repo\"))

    (git/commit repo \"HEAD\")
    (git/commits repo {:from \"v1.0.0\" :to \"v1.1.0\"})
    (git/changed-files repo \"v1.0.0\" \"v1.1.0\")
    (git/tags repo)
    (git/branches repo)

  All functions shell out to the `git` executable, so git must be installed.
  Nothing in this namespace modifies the repository."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import (java.time OffsetDateTime)
           (java.util Date)))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defrecord Repo [path git-dir bare? git-binary])

(def ^:private base-args
  ;; Keep output machine-friendly regardless of the user's git config.
  ["--no-pager" "-c" "color.ui=never" "-c" "core.quotepath=off" "-c" "log.showSignature=false"])

(defn- run-git
  "Runs git and returns {:exit :out :err}. Never throws on a git error."
  [git-binary dir args]
  (apply shell/sh git-binary (concat base-args (map str args) [:dir dir])))

(defn- run-git!
  [git-binary dir args]
  (let [{:keys [exit out err]} (run-git git-binary dir args)]
    (if (zero? exit)
      out
      (throw (ex-info (str "git command failed: git " (str/join " " args) "\n" (str/trim err))
                      {:type ::git-error :exit exit :args (vec args) :dir dir :err err})))))

(defn connect
  "Opens a connection to the git repository at `path` (the working tree, any
  sub-directory of it, or a bare repository). Returns a `Repo` record to pass
  to the other functions in this namespace.

  Options:
    :git-binary  path to the git executable (default \"git\")

  Throws ex-info if `path` is not inside a git repository."
  ([path] (connect path {}))
  ([path {:keys [git-binary] :or {git-binary "git"}}]
   (let [f (io/file (str path))]
     (when-not (.isDirectory f)
       (throw (ex-info (str "Not a directory: " path) {:type ::not-a-directory :path (str path)})))
     (let [dir      (.getCanonicalPath f)
           git-dir  (str/trim (run-git! git-binary dir ["rev-parse" "--absolute-git-dir"]))
           bare?    (= "true" (str/trim (run-git! git-binary dir ["rev-parse" "--is-bare-repository"])))
           top      (if bare?
                      git-dir
                      (str/trim (run-git! git-binary dir ["rev-parse" "--show-toplevel"])))]
       (->Repo top git-dir bare? git-binary)))))

(defn repo?
  "True if `x` is a connection returned by `connect`."
  [x]
  (instance? Repo x))

(defn- ensure-repo [repo]
  (when-not (repo? repo)
    (throw (ex-info "Expected a Repo connection created with clojure-git.core/connect"
                    {:type ::invalid-repo :value repo})))
  repo)

(defn git
  "Escape hatch: runs `git <args>` in the repository and returns stdout as a
  string. Throws ex-info if git exits non-zero."
  [repo & args]
  (ensure-repo repo)
  (run-git! (:git-binary repo) (:path repo) args))

(defn- git-quiet
  "Like `git` but returns nil instead of throwing when git fails."
  [repo & args]
  (ensure-repo repo)
  (let [{:keys [exit out]} (run-git (:git-binary repo) (:path repo) args)]
    (when (zero? exit) out)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private RS "\u001e")
(def ^:private US "\u001f")

(defn- check-ref
  "Guards against empty refs and refs that git would read as options."
  [ref]
  (let [s (str ref)]
    (when (or (str/blank? s) (str/starts-with? s "-"))
      (throw (ex-info (str "Invalid ref: " (pr-str ref)) {:type ::invalid-ref :ref ref})))
    s))

(defn- parse-date [s]
  (when-not (str/blank? s)
    (Date/from (.toInstant (OffsetDateTime/parse (str/trim s))))))

(defn- date-arg [x]
  (if (instance? Date x) (str (.toInstant ^Date x)) (str x)))

(defn- strip-email [s]
  (when-not (str/blank? s)
    (str/replace s #"^<|>$" "")))

(defn- blank->nil [s]
  (when-not (str/blank? s) s))

(defn- records [out]
  (->> (str/split out (re-pattern RS))
       (remove str/blank?)
       ;; for-each-ref / log append a newline after every record
       (map #(str/replace % #"\n\z" ""))))

(defn- fields [rec]
  (str/split rec (re-pattern US) -1))

(defn- as-paths [opts]
  (let [{:keys [path paths]} opts]
    (cond-> (vec (or paths []))
      path (conj path))))

;; ---------------------------------------------------------------------------
;; Refs
;; ---------------------------------------------------------------------------

(defn resolve-ref
  "Returns the full commit SHA that `ref` (branch, tag, SHA, HEAD~2, ...)
  points to, or nil if it does not resolve to a commit."
  [repo ref]
  (some-> (git-quiet repo "rev-parse" "--verify" "--quiet" (str (check-ref ref) "^{commit}"))
          str/trim
          blank->nil))

(defn head
  "Full SHA of HEAD, or nil in an empty repository."
  [repo]
  (resolve-ref repo "HEAD"))

(defn current-branch
  "Name of the checked-out branch, or nil when HEAD is detached."
  [repo]
  (some-> (git-quiet repo "symbolic-ref" "--short" "-q" "HEAD") str/trim blank->nil))

(defn merge-base
  "Best common ancestor of two refs, or nil if they share no history."
  [repo a b]
  (some-> (git-quiet repo "merge-base" (check-ref a) (check-ref b)) str/trim blank->nil))

(defn ancestor?
  "True if `ancestor` is reachable from `descendant`."
  [repo ancestor descendant]
  (zero? (:exit (run-git (:git-binary (ensure-repo repo)) (:path repo)
                         ["merge-base" "--is-ancestor" (check-ref ancestor) (check-ref descendant)]))))

;; ---------------------------------------------------------------------------
;; Changed files
;; ---------------------------------------------------------------------------

(def ^:private status-codes
  {\A :added \M :modified \D :deleted \R :renamed \C :copied
   \T :type-changed \U :unmerged \X :unknown})

(defn- parse-name-status
  "Parses `--name-status -z` output into {new-path entry}."
  [out]
  (loop [toks (remove str/blank? (str/split out #"\u0000"))
         acc  []]
    (if-let [code (first toks)]
      (let [status (get status-codes (first code) :unknown)
            score  (when (> (count code) 1) (parse-long (subs code 1)))]
        (if (#{:renamed :copied} status)
          (let [[old new] (take 2 (rest toks))]
            (recur (drop 3 toks)
                   (conj acc {:path new :old-path old :status status :similarity score})))
          (recur (drop 2 toks)
                 (conj acc {:path (second toks) :status status}))))
      acc)))

(defn- parse-numstat
  "Parses `--numstat -z` output into {new-path {:additions :deletions :binary?}}."
  [out]
  (loop [toks (remove str/blank? (str/split out #"\u0000"))
         acc  {}]
    (if-let [tok (first toks)]
      (let [[a d p] (str/split tok #"\t" 3)
            binary? (= "-" a)
            stats   {:additions (when-not binary? (parse-long a))
                     :deletions (when-not binary? (parse-long d))
                     :binary?   binary?}]
        (if (str/blank? p)
          ;; rename/copy: path is split into the next two tokens (old, new)
          (recur (drop 3 toks) (assoc acc (nth toks 2 nil) stats))
          (recur (rest toks) (assoc acc p stats))))
      acc)))

(defn- diff-tree-files
  "Files that differ between `base` and `target`. When `base` is nil,
  `target` is compared with the empty tree (used for root commits)."
  [repo base target paths]
  (let [revs    (if base [base target] ["--root" target])
        common  ["diff-tree" "-r" "-z" "-M" "--no-commit-id"]
        tail    (concat revs ["--"] paths)
        status  (parse-name-status (apply git repo (concat common ["--name-status"] tail)))
        numstat (parse-numstat (apply git repo (concat common ["--numstat"] tail)))]
    (mapv (fn [{:keys [path] :as e}]
            (into (into {} (remove (comp nil? val) e)) (get numstat path)))
          status)))

(defn- first-parent [repo sha]
  (let [out (str/trim (git repo "rev-list" "--parents" "-n" "1" sha "--"))]
    (second (str/split out #" "))))

(defn changed-files
  "Files changed by a single commit, or between two refs.

    (changed-files repo \"abc123\")            ; files changed by that commit
    (changed-files repo \"v1.0\" \"v1.1\")       ; tree diff from v1.0 to v1.1
    (changed-files repo \"main\" \"feature\" {:merge-base true :paths [\"src\"]})

  For a single commit the diff is against its first parent (or the empty
  tree for a root commit), so merge commits show what the merge brought in.

  Options (range form):
    :merge-base  diff from the merge base of the two refs (like `git diff a...b`)
    :path/:paths limit to these paths

  Each entry: {:path :status :additions :deletions :binary?} plus
  :old-path and :similarity for renames and copies. :status is one of
  :added :modified :deleted :renamed :copied :type-changed :unmerged :unknown."
  ([repo ref]
   (let [sha (or (resolve-ref repo ref)
                 (throw (ex-info (str "Unknown commit: " ref) {:type ::unknown-ref :ref ref})))]
     (diff-tree-files repo (first-parent repo sha) sha [])))
  ([repo from to]
   (changed-files repo from to {}))
  ([repo from to opts]
   (let [to-sha   (or (resolve-ref repo to)
                      (throw (ex-info (str "Unknown ref: " to) {:type ::unknown-ref :ref to})))
         from-sha (or (resolve-ref repo from)
                      (throw (ex-info (str "Unknown ref: " from) {:type ::unknown-ref :ref from})))
         base     (if (:merge-base opts) (merge-base repo from-sha to-sha) from-sha)]
     (diff-tree-files repo base to-sha (as-paths opts)))))

;; ---------------------------------------------------------------------------
;; Commits
;; ---------------------------------------------------------------------------

(def ^:private commit-format
  (str "--format=" "%x1e"
       (str/join "%x1f" ["%H" "%h" "%P" "%T"
                         "%an" "%ae" "%aI"
                         "%cn" "%ce" "%cI"
                         "%D" "%s" "%b"])))

(defn- parse-refs [s]
  (when-not (str/blank? s)
    (let [parts (map str/trim (str/split s #","))]
      {:head?    (boolean (some #(str/starts-with? % "HEAD") parts))
       :tags     (vec (keep #(second (re-matches #"tag: (.+)" %)) parts))
       :branches (vec (keep #(cond
                               (str/starts-with? % "tag: ") nil
                               (= % "HEAD") nil
                               (str/starts-with? % "HEAD -> ") (subs % 8)
                               :else %)
                            parts))})))

(defn- parse-commit [rec]
  (let [[sha short parents tree an ae ad cn ce cd refs subject body] (fields rec)
        parents (if (str/blank? parents) [] (str/split parents #" "))
        body    (str/trim (or body ""))]
    {:sha       sha
     :short-sha short
     :tree      tree
     :parents   parents
     :merge?    (> (count parents) 1)
     :author    {:name an :email ae :date (parse-date ad)}
     :committer {:name cn :email ce :date (parse-date cd)}
     :subject   subject
     :body      body
     :message   (if (str/blank? body) subject (str subject "\n\n" body))
     :refs      (or (parse-refs refs) {:head? false :tags [] :branches []})}))

(defn- range-args
  "Revision arguments for a commit range.
   :from/:to  -> commits reachable from :to (default HEAD) but not from :from.
   :inclusive -> also include :from itself.
   :rev       -> any raw revision expression, e.g. \"main..feature\".
   :all       -> every ref."
  [{:keys [from to rev all inclusive]}]
  (cond
    all       ["--all"]
    rev       [(check-ref rev)]
    from      (if inclusive
                [(check-ref (or to "HEAD")) "--not" (str (check-ref from) "^@")]
                [(str (check-ref from) ".." (check-ref (or to "HEAD")))])
    to        [(check-ref to)]
    :else     ["HEAD"]))

(defn- log-flags
  [{:keys [limit skip author committer since until grep no-merges merges-only
           first-parent reverse topo-order]}]
  (cond-> []
    limit        (conj (str "--max-count=" (long limit)))
    skip         (conj (str "--skip=" (long skip)))
    author       (conj (str "--author=" author))
    committer    (conj (str "--committer=" committer))
    since        (conj (str "--since=" (date-arg since)))
    until        (conj (str "--until=" (date-arg until)))
    grep         (conj (str "--grep=" grep))
    no-merges    (conj "--no-merges")
    merges-only  (conj "--merges")
    first-parent (conj "--first-parent")
    topo-order   (conj "--topo-order")
    reverse      (conj "--reverse")))

(defn- commit-files [repo {:keys [sha parents]}]
  (diff-tree-files repo (first parents) sha []))

(defn commits
  "Returns a vector of commit maps, newest first.

  Range options:
    :from       exclude commits reachable from this ref (tag, branch, SHA)
    :to         include commits reachable from this ref (default \"HEAD\")
    :inclusive  when true, include the :from commit itself as well
    :rev        a raw revision expression instead, e.g. \"main..feature\"
    :all        commits reachable from any ref

  Filters:
    :limit :skip :author :committer :grep
    :since :until        (strings like \"2 weeks ago\" / \"2024-01-01\", or java.util.Date)
    :path / :paths       only commits touching these paths
    :no-merges :merges-only :first-parent :topo-order :reverse

  Extras:
    :files      when true, attach :files (see `changed-files`) to each commit.
                Costs two extra git calls per commit.

  Each commit: {:sha :short-sha :tree :parents :merge? :author :committer
                :subject :body :message :refs}
  where :author/:committer are {:name :email :date} (date is java.util.Date)
  and :refs is {:head? :branches :tags} for refs that point at the commit."
  ([repo] (commits repo {}))
  ([repo opts]
   (let [out (apply git repo "log" commit-format
                    (concat (log-flags opts) (range-args opts) ["--"] (as-paths opts)))
         cs  (map parse-commit (records out))]
     (if (:files opts)
       (mapv #(assoc % :files (commit-files repo %)) cs)
       (vec cs)))))

(defn commit
  "Returns a single commit map for `ref` (SHA, tag, branch, HEAD~1, ...), or
  nil if it does not exist. Includes :files (see `changed-files`) unless
  called with {:files false}."
  ([repo ref] (commit repo ref {}))
  ([repo ref {:keys [files] :or {files true}}]
   (when-let [sha (resolve-ref repo ref)]
     (let [c (-> (git repo "log" "-n" "1" commit-format sha "--") records first parse-commit)]
       (if files (assoc c :files (commit-files repo c)) c)))))

(defn commits-between
  "Commits after `from` up to and including `to` (like `git log from..to`).
  Accepts the same options as `commits`."
  ([repo from to] (commits-between repo from to {}))
  ([repo from to opts]
   (commits repo (assoc opts :from from :to to))))

(defn count-commits
  "Number of commits matching the same options as `commits`."
  ([repo] (count-commits repo {}))
  ([repo opts]
   (-> (apply git repo "rev-list" "--count"
              (concat (log-flags (dissoc opts :reverse)) (range-args opts) ["--"] (as-paths opts)))
       str/trim
       parse-long)))

;; ---------------------------------------------------------------------------
;; Log & diff (text output)
;; ---------------------------------------------------------------------------

(defn- file-stats [files]
  {:files     (count files)
   :additions (reduce + 0 (keep :additions files))
   :deletions (reduce + 0 (keep :deletions files))})

(defn log
  "Structured log: a vector of commit maps (newest first), each with the
  files it changed and a :stats summary.

  Accepts every option of `commits` (:from :to :inclusive :rev :limit
  :author :since :until :path ...). Pass {:files false} to skip the file
  lookups when you only need commit metadata (faster on long histories).

  Each entry is a `commits` map plus:
    :files  [{:path :status :additions :deletions :binary? ...}]
    :stats  {:files 2 :additions 10 :deletions 3}"
  ([repo] (log repo {}))
  ([repo opts]
   (let [files? (get opts :files true)]
     (cond->> (commits repo (assoc opts :files files?))
       files? (mapv #(assoc % :stats (file-stats (:files %))))))))

(defn log-text
  "Returns `git log` output as a string, for display or quick inspection.
  Accepts the same range/filter options as `commits`, plus:
    :format  :oneline :short :medium :full :fuller or a custom format string
             (e.g. \"%h %an %s\"). Default :medium.
    :stat    include a diffstat per commit
    :patch   include the full patch per commit
    :graph   draw the ASCII commit graph"
  ([repo] (log-text repo {}))
  ([repo {:keys [format stat patch graph] :or {format :medium} :as opts}]
   (apply git repo "log"
          (concat [(if (keyword? format)
                     (str "--format=" (name format))
                     (str "--format=" format))]
                  (when stat ["--stat"])
                  (when patch ["--patch"])
                  (when graph ["--graph"])
                  (log-flags opts)
                  (range-args opts)
                  ["--"]
                  (as-paths opts)))))

(defn diff
  "Returns a unified diff as a string.

    (diff repo \"abc123\")                 ; patch introduced by one commit
    (diff repo \"v1.0\" \"v1.1\")            ; patch between two refs
    (diff repo \"v1.0\" \"v1.1\" {:paths [\"src\"] :context 5})

  Options: :path/:paths, :context (lines), :merge-base, :stat (diffstat only)."
  ([repo ref]
   (let [sha (or (resolve-ref repo ref)
                 (throw (ex-info (str "Unknown commit: " ref) {:type ::unknown-ref :ref ref})))
         p   (first-parent repo sha)]
     (apply git repo "diff-tree" "-p" "-M" "--no-commit-id"
            (concat (if p [p sha] ["--root" sha]) ["--"]))))
  ([repo from to] (diff repo from to {}))
  ([repo from to {:keys [context stat] :as opts}]
   (let [from-sha (or (resolve-ref repo from) (throw (ex-info (str "Unknown ref: " from) {:type ::unknown-ref :ref from})))
         to-sha   (or (resolve-ref repo to) (throw (ex-info (str "Unknown ref: " to) {:type ::unknown-ref :ref to})))
         base     (if (:merge-base opts) (merge-base repo from-sha to-sha) from-sha)]
     (apply git repo "diff" "-M"
            (concat (when context [(str "-U" (long context))])
                    (when stat ["--stat"])
                    [base to-sha "--"]
                    (as-paths opts))))))

(defn file-at
  "Contents of `path` (relative to the repo root) at `ref`, or nil if the
  file does not exist there."
  [repo ref path]
  (git-quiet repo "show" (str (check-ref ref) ":" path)))

;; ---------------------------------------------------------------------------
;; Tags
;; ---------------------------------------------------------------------------

(def ^:private tag-format
  (str "--format=%1e"
       (str/join "%1f" ["%(refname:short)" "%(objectname)" "%(objecttype)" "%(*objectname)"
                        "%(taggername)" "%(taggeremail)" "%(taggerdate:iso-strict)"
                        "%(creatordate:iso-strict)" "%(contents)"])))

(defn- parse-tag [rec]
  (let [[name obj type peeled tn te td cd contents] (fields rec)
        annotated? (= "tag" type)]
    {:name       name
     :sha        (if annotated? peeled obj)  ; commit the tag points to
     :tag-sha    (when annotated? obj)
     :annotated? annotated?
     :tagger     (when annotated? {:name tn :email (strip-email te) :date (parse-date td)})
     :date       (parse-date cd)
     :message    (when annotated? (str/trim contents))}))

(defn- ref-filter-args [{:keys [contains merged no-merged points-at]}]
  (cond-> []
    contains  (conj (str "--contains=" (check-ref contains)))
    merged    (conj (str "--merged=" (check-ref merged)))
    no-merged (conj (str "--no-merged=" (check-ref no-merged)))
    points-at (conj (str "--points-at=" (check-ref points-at)))))

(defn tags
  "Returns a vector of tags, oldest first by default.

  Options:
    :pattern    glob on the tag name, e.g. \"v1.*\"
    :sort       any `git for-each-ref --sort` key, e.g. \"-creatordate\" or
                \"version:refname\" (default \"creatordate\")
    :contains   only tags whose commit contains this ref
    :merged     only tags reachable from this ref
    :no-merged  only tags not reachable from this ref
    :points-at  only tags pointing at this ref

  Each tag: {:name :sha :tag-sha :annotated? :tagger :date :message}
  :sha is always the commit the tag points to."
  ([repo] (tags repo {}))
  ([repo {:keys [pattern sort] :or {sort "creatordate"} :as opts}]
   (->> (apply git repo "for-each-ref" tag-format (str "--sort=" sort)
               (concat (ref-filter-args opts)
                       [(str "refs/tags/" (or pattern ""))]))
        records
        (mapv parse-tag))))

(defn tag
  "A single tag by name, or nil."
  [repo tag-name]
  (first (filter #(= tag-name (:name %)) (tags repo {:pattern tag-name}))))

(defn tags-between
  "Tags reachable from `to` but not from `from`: i.e. tags created after
  `from` up to and including `to`. Accepts the same options as `tags`."
  ([repo from to] (tags-between repo from to {}))
  ([repo from to opts]
   (tags repo (assoc opts :merged to :no-merged from))))

(defn latest-tag
  "Name of the most recent tag reachable from `ref` (default HEAD), or nil."
  ([repo] (latest-tag repo "HEAD"))
  ([repo ref]
   (some-> (git-quiet repo "describe" "--tags" "--abbrev=0" (check-ref ref)) str/trim blank->nil)))

;; ---------------------------------------------------------------------------
;; Branches
;; ---------------------------------------------------------------------------

(def ^:private branch-format
  (str "--format=%1e"
       (str/join "%1f" ["%(refname)" "%(refname:short)" "%(objectname)" "%(upstream:short)"
                        "%(upstream:track)" "%(HEAD)" "%(committerdate:iso-strict)"
                        "%(contents:subject)"])))

(defn- parse-branch [rec]
  (let [[ref short sha upstream track head date subject] (fields rec)
        track   (str/trim (or track ""))]
    {:name     short
     :ref      ref
     :sha      sha
     :remote?  (str/starts-with? ref "refs/remotes/")
     :current? (= "*" head)
     :upstream (blank->nil upstream)
     :ahead    (some-> (re-find #"ahead (\d+)" track) second parse-long)
     :behind   (some-> (re-find #"behind (\d+)" track) second parse-long)
     :date     (parse-date date)
     :subject  subject}))

(defn branches
  "Returns a vector of branches.

  Options:
    :type       :local (default), :remote or :all
    :sort       `git for-each-ref --sort` key (default \"refname\"),
                e.g. \"-committerdate\" for most recently updated first
    :contains   only branches containing this ref
    :merged     only branches merged into this ref
    :no-merged  only branches not merged into this ref

  Each branch: {:name :ref :sha :remote? :current? :upstream :ahead :behind
                :date :subject}"
  ([repo] (branches repo {}))
  ([repo {:keys [type sort] :or {type :local sort "refname"} :as opts}]
   (let [patterns (case type
                    :local  ["refs/heads/"]
                    :remote ["refs/remotes/"]
                    :all    ["refs/heads/" "refs/remotes/"])]
     (->> (apply git repo "for-each-ref" branch-format (str "--sort=" sort)
                 (concat (ref-filter-args opts) patterns))
          records
          (map parse-branch)
          (remove #(str/ends-with? (:ref %) "/HEAD"))
          vec))))

;; ---------------------------------------------------------------------------
;; Misc
;; ---------------------------------------------------------------------------

(defn contributors
  "Authors with commit counts, most active first. Accepts the same
  range/filter options as `commits`.
  Each entry: {:name :email :commits}"
  ([repo] (contributors repo {}))
  ([repo opts]
   (->> (apply git repo "shortlog" "-s" "-n" "-e"
               (concat (log-flags opts) (range-args opts) ["--"] (as-paths opts)))
        str/split-lines
        (keep #(when-let [[_ n name email] (re-matches #"\s*(\d+)\t(.*?)\s*<([^>]*)>\s*" %)]
                 {:name name :email email :commits (parse-long n)}))
        vec)))

(defn remotes
  "Configured remotes: [{:name :fetch-url :push-url}]."
  [repo]
  (->> (str/split-lines (git repo "remote" "-v"))
       (keep #(re-matches #"(\S+)\t(\S+) \((fetch|push)\)" %))
       (reduce (fn [acc [_ name url kind]]
                 (assoc-in acc [name (keyword (str kind "-url"))] url))
               (sorted-map))
       (mapv (fn [[name m]] (assoc m :name name)))))
