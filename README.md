# clojure-git

A small, dependency-free Clojure library for reading git data from a local
repository: commits, single commits, tags, branches, logs, diffs and changed
files, with ranges between commits or tags.

It shells out to the `git` executable (must be on your `PATH`), never modifies
the repository, and works on Clojure 1.11+ and Babashka.

## Add it to your project

### deps.edn (Clojure CLI)

Point at the folder on disk:

```clojure
{:deps {io.github.mroma82/clojure-git {:local/root "../clojure-git"}}}
```

Or straight from GitHub:

```clojure
{:deps {io.github.mroma82/clojure-git {:git/tag "v0.1.0" :git/sha "792e931"}}}
```

### Leiningen

```bash
cd clojure-git && lein install      # installs io.github.mroma82/clojure-git 0.1.0 to ~/.m2
```

```clojure
:dependencies [[org.clojure/clojure "1.12.0"]
               [io.github.mroma82/clojure-git "0.1.0"]]
```


## Quick start

```clojure
(require '[clojure-git.core :as git])

(def repo (git/connect "/path/to/repo"))   ; the connection you pass everywhere

(git/current-branch repo)                   ;=> "main"
(git/head repo)                             ;=> "3f2c9a…"
```

### Commits

```clojure
(git/commits repo)                                  ; all commits from HEAD, newest first
(git/commits repo {:limit 20 :no-merges true})

;; ranges (like `git log from..to`) – refs can be tags, branches or SHAs
(git/commits repo {:from "v1.0.0" :to "v1.1.0"})
(git/commits-between repo "v1.0.0" "v1.1.0")
(git/commits repo {:from "abc123" :to "def456" :inclusive true})  ; include abc123 too
(git/commits repo {:rev "main..feature"})                         ; raw revision expression

;; filters
(git/commits repo {:author "ada" :since "2 weeks ago" :path "src/" :grep "fix"})

;; attach changed files to each commit
(git/commits repo {:from "v1.0.0" :files true})

(git/count-commits repo {:from "v1.0.0"})
```

A commit looks like:

```clojure
{:sha "3f2c9a…" :short-sha "3f2c9a1" :tree "…" :parents ["…"] :merge? false
 :author    {:name "Ada" :email "ada@example.com" :date #inst "2024-05-01T10:00:00Z"}
 :committer {:name "Ada" :email "ada@example.com" :date #inst "2024-05-01T10:00:00Z"}
 :subject "Fix parser" :body "Longer explanation" :message "Fix parser\n\nLonger explanation"
 :refs {:head? true :branches ["main"] :tags ["v1.1.0"]}}
```

### A single commit

```clojure
(git/commit repo "abc123")           ; includes :files
(git/commit repo "v1.1.0")           ; tags and branches work too
(git/commit repo "HEAD~2" {:files false})
```

### Changed files

```clojure
(git/changed-files repo "abc123")                   ; one commit (vs its first parent)
(git/changed-files repo "v1.0.0" "v1.1.0")          ; between two refs
(git/changed-files repo "main" "feature" {:merge-base true :paths ["src"]})
;=> [{:path "src/a.clj" :status :modified :additions 4 :deletions 1 :binary? false}
;    {:path "src/b.clj" :old-path "b.clj" :status :renamed :similarity 100 …}]
```

`:status` is one of `:added :modified :deleted :renamed :copied :type-changed :unmerged :unknown`.

### Tags

```clojure
(git/tags repo)                                     ; oldest first
(git/tags repo {:sort "-creatordate"})              ; newest first
(git/tags repo {:pattern "v1.*" :sort "version:refname"})
(git/tags-between repo "v1.0.0" "HEAD")             ; tags after v1.0.0 up to HEAD
(git/tag repo "v1.0.0")
(git/latest-tag repo)                               ; nearest tag reachable from HEAD
;=> {:name "v1.0.0" :sha "<commit sha>" :tag-sha "<tag object>" :annotated? true
;    :tagger {:name … :email … :date …} :date #inst "…" :message "First release"}
```

### Branches

```clojure
(git/branches repo)                                 ; local
(git/branches repo {:type :all :sort "-committerdate"})
(git/branches repo {:merged "main"})
;=> [{:name "main" :ref "refs/heads/main" :sha "…" :current? true :remote? false
;     :upstream "origin/main" :ahead 1 :behind nil :date #inst "…" :subject "…"}]
```

### Log

`log` returns a vector of commit maps, each with the files it changed and a
`:stats` summary. It takes all the `commits` options.

```clojure
(git/log repo {:from "v1.0.0" :to "v1.1.0"})
(git/log repo {:limit 50 :files false})              ; metadata only, faster
;=> [{:sha "93d295…" :short-sha "93d295b" :subject "Add b, extend a" …
;     :files [{:path "a.txt" :status :modified :additions 1 :deletions 0 :binary? false}
;             {:path "b.txt" :status :added :additions 1 :deletions 0 :binary? false}]
;     :stats {:files 2 :additions 2 :deletions 0}}]
```

### Text logs, diffs and more

```clojure
(println (git/log-text repo {:format :oneline :from "v1.0.0" :graph true}))
(git/log-text repo {:format "%h %an %s" :limit 10}) ; any git pretty format
(git/diff repo "abc123")                            ; patch for one commit
(git/diff repo "v1.0.0" "v1.1.0" {:paths ["src"]})
(git/diff repo "v1.0.0" "v1.1.0" {:stat true})
(git/file-at repo "v1.0.0" "README.md")             ; file contents at a revision
(git/contributors repo {:from "v1.0.0"})            ; [{:name :email :commits}]
(git/remotes repo)
(git/merge-base repo "main" "feature")
(git/ancestor? repo "v1.0.0" "main")
(git/resolve-ref repo "v1.0.0")                     ; => full SHA or nil

(git/git repo "rev-list" "--count" "HEAD")          ; escape hatch: raw git, returns stdout
```

Errors from git are thrown as `ex-info` with `{:type :clojure-git.core/git-error :exit … :err …}`.
Lookups of a single thing (`commit`, `tag`, `resolve-ref`, `file-at`, `latest-tag`) return `nil` when it doesn't exist.

## Running the tests

```bash
clojure -X:test     # Clojure CLI
lein test           # Leiningen
bb test             # Babashka
```

The tests build a throwaway repository in a temp folder.
