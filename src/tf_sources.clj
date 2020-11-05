(ns tf-sources
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http-client]))

(defn parse-tag
  "Tries to parse a tag into an optional prefix string, major, minor and
  patch integers, and an optional -rc integer.

  Some examples to explain:

  v2.13.3   ;; no rc value
  => {:unparsed-tag \"v2.13.3\"
      :prefix \"v\"
      :major 2
      :minor 13
      :patch 3}

  v1.0 ;; no patch nor rc value
  => {:unparsed-tag \"v1.0\"
      :prefix \"v\"
      :major 1
      :minor 0}

  v12.1.33-rc14
  => {:unparsed-tag \"v12.1.33-rc14\"
      :prefix \"v\"
      :major 12
      :minor 1
      :patch 33
      :rc 14}
  "
  [tag]
  (let [part->idx {:major 2
                   :minor 3
                   :patch 4}

        re-prefix-major-minor-patch-rc
        #"([^\d]*)(\d+)?\.?(\d+)?\.?(\d+)?(-rc\d+)?"

        parts (re-find re-prefix-major-minor-patch-rc tag)

        without-rc (reduce
                     (fn [acc [key idx]]
                       (if-let [v-str (nth parts idx)]
                         (assoc acc key (Long/parseLong v-str))
                         acc))
                     {:unparsed-tag tag
                      :prefix (nth parts 1)}
                     part->idx)]

    (conj without-rc (when-let [rc-str (nth parts 5)]
                       [:rc (Long/parseLong
                              (nth (re-find #"-rc(\d+)" rc-str)
                                   1))]))))

(def ->sortable-tag
  "Creates a sortable representation of a parsed tag value."
  (juxt #(get % :major 0)
        #(get % :minor 0)
        #(get % :patch 0)
        #(if-let [rc (:rc %)]
           (+ Long/MIN_VALUE rc)
           0)))

(defn max-tag
  "Returns the tag within the parsed-tags collection with the highest
  semantic version. If the optional for-major is specified, the tag
  with the highest semantic version with that :major value within
  parsed-tags is returned."
  ([parsed-tags]
   (last (sort-by ->sortable-tag parsed-tags)))
  ([for-major parsed-tags]
   (max-tag (filter #(= for-major (:major %)) parsed-tags))))

(defn find-relevant-terraform-file-paths
  "Recursively find .tf files that are not hidden, and that are not
  contained under hidden folders."
  [dir]
  (let [dir (java.io.File. dir)
        files (file-seq dir)
        file-paths (set (map #(.getPath %) files))
        hidden-files (filter #(.isHidden %) files)
        hidden-files-paths (set (map #(.getPath %) hidden-files))]
    (filter
      (fn [path]
        (and (str/ends-with? path ".tf")
             (not-any? #(str/starts-with? path %) hidden-files-paths)))
      file-paths)))

(defn find-referenced-modules
  "Finds github urls within parenthesis in the file-content, was is a
  string. Returns a set of parsed urls. Each parsed url is a map
  describing the github account, repository and tag information."
  [file-content]
  (let [re-github-within-parenthesis
        #"\"([^\s\"]*github[^\"]*)\""

        find-github-urls
        #(->> %
              (re-seq re-github-within-parenthesis)
              (map second)
              set)]
    (->> (find-github-urls file-content)
         (map (fn [github-url]
                (let [re-account-repository-reference
                      #"github.com:([^/]+)/([^.?/]+).*\?ref=(.*)"

                      parts
                      (re-find re-account-repository-reference github-url)]
                  {:original-github-url github-url
                   :account (nth parts 1)
                   :repository (nth parts 2)
                   :tag (parse-tag (nth parts 3))})))
         set)))

(defn find-terraform-files-with-module-references
  "Returns a map of file-path to maps of file content and referenced
  modules."
  [dir]
  (->> (find-relevant-terraform-file-paths dir)
       (map (fn [terraform-file]
              (let [file-content (slurp terraform-file)
                    referenced-modules (find-referenced-modules file-content)]
                [terraform-file {:file-content file-content
                                 :referenced-modules referenced-modules}])))
       (filter #(seq (:referenced-modules (second %))))
       (into {})))

(defn fetch-available-module-tags-from-github
  "Returns a vector of tag data retrieved from the specified github
  account/repository."
  [{:keys [account repository]}]
  (try
    (let [nth-int #(Integer/parseInt (nth %1 %2))
          tags (-> (str "https://api.github.com/repos/" account "/" repository "/git/refs/tags")
                   (http-client/get
                     (when-let [oauth-token (System/getenv "GITHUB_TOKEN")]
                       {:headers {"Authorization" (str "token " oauth-token)}}))
                   :body
                   (json/parse-string keyword))]
      (mapv #(parse-tag (subs (:ref %) (count "refs/tags/")))
            tags))
    (catch Exception exc
      (throw (Exception. (str "\n\nCould not fetch tags for " account "/" repository ". "
                              "Perhaps the repository doesn't exist, or you don't have access to it. "
                              "This can be related to a wrong or missing GITHUB_TOKEN environment variable."))))))

(defn update-references
  "Patches all github urls used as sources in .tf files under dir with
  updated tag versions."
  [{:keys [dir strategy] :or {strategy :highest-semver}}]
  (when-not dir
    (println "please specify the dir option")
    (System/exit -1))
  (let [->module-id
        #(select-keys % [:account :repository])

        file-path->contents-and-module-refs
        (find-terraform-files-with-module-references dir)

        unique-module-ids
        (->> (vals file-path->contents-and-module-refs)
             (map :referenced-modules)
             (apply concat)
             (map ->module-id)
             set)

        _ (do
            (println (str "* found " (count unique-module-ids) " referenced module repositories"
                          " across " (count file-path->contents-and-module-refs)
                          " files with module references"))
            (print "* loading module tags from github: ") (flush))

        module-id->available-tags
        (->> unique-module-ids
             (map (fn [module-id]
                    (print ".")
                    (flush)
                    [module-id (fetch-available-module-tags-from-github module-id)]))
             (into {}))

        tag-selector ({:highest-semver
                       (fn [orig-tag available-tags]
                         (max-tag available-tags))

                       :highest-semver-current-major
                       (fn [orig-tag available-tags]
                         (max-tag (:major orig-tag) available-tags))} strategy)]
    (println)
    (println "* patched files:")
    (doseq [[file-path contents-and-module-refs] file-path->contents-and-module-refs]
      (let [updated-file-content(reduce
                                  (fn [acc referenced-module]
                                    (let [module-id (->module-id referenced-module)
                                          orig-url (:original-github-url referenced-module)
                                          orig-url-tag (:tag referenced-module)
                                          new-url-tag (let [known-tags (module-id->available-tags module-id)]
                                                        (if (some #(= (:unparsed-tag orig-url-tag) %)
                                                                  (map :unparsed-tag known-tags))
                                                          (tag-selector orig-url-tag known-tags)
                                                          orig-url-tag))]
                                      (str/replace acc
                                                   orig-url
                                                   (str/replace orig-url
                                                                (:unparsed-tag orig-url-tag)
                                                                (:unparsed-tag new-url-tag)))))
                                  (:file-content contents-and-module-refs)
                                  (:referenced-modules contents-and-module-refs))]
        (when (not= (:file-content contents-and-module-refs) updated-file-content)
          (spit file-path updated-file-content)
          (println (str "  - " file-path)))))
    (println "* done")))
