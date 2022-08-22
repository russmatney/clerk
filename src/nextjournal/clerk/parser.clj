(ns nextjournal.clerk.parser
  "Clerk's Parser turns Clojure & Markdown files and strings into Clerk documents."
  (:refer-clojure :exclude [hash read-string])
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [nextjournal.markdown :as markdown]
            [nextjournal.markdown.parser :as markdown.parser]
            [nextjournal.markdown.transform :as markdown.transform]))

(defn ns? [form]
  (and (seq? form) (= 'ns (first form))))

(defn remove-leading-semicolons [s]
  (str/replace s #"^[;]+" ""))

(defn parse-visibility [form visibility]
  (when-let [visibility-map (and visibility (cond->> visibility (not (map? visibility)) (hash-map :code)))]
    (when-not (and (every? #{:code :result} (keys visibility-map))
                   (every? #{:hide :show :fold} (vals visibility-map)))
      (throw (ex-info "Invalid `:nextjournal.clerk/visibility`, please pass a map with `:code` and `:result` keys, allowed values are `:hide`, `:show` and `:fold`."
                      (cond-> {:visibility visibility}
                        form (assoc :form form)))))
    visibility-map))

#_(parse-visibility nil nil)
#_(parse-visibility nil {:code :fold :result :hide})

(defn ->visibility [form]
  (parse-visibility form (-> form meta :nextjournal.clerk/visibility)))

#_(->visibility (quote ^{:nextjournal.clerk/visibility :fold} (ns foo)))
#_(->visibility '(foo :bar))
#_(->visibility (quote (ns foo {:nextjournal.clerk/visibility {:code :fold :result :hide}})))
#_(->visibility (quote ^{:nextjournal.clerk/visibility {:code :fold :result :hide}} (ns foo)))
#_(->visibility (quote ^{:nextjournal.clerk/visibility :hidden} (ns foo)))
#_(->visibility (quote ^{:nextjournal.clerk/visibility "bam"} (ns foo)))
#_(->visibility (quote ^{:nextjournal.clerk/visibility #{:hide-ns}} (do :foo)))

(defn ->doc-visibility [first-form]
  (merge {:code :show :result :show}
         (when (ns? first-form)
           (parse-visibility first-form (merge (-> first-form second meta :nextjournal.clerk/visibility)
                                               (some :nextjournal.clerk/visibility first-form))))))


#_(->doc-visibility '(ns foo "my docs" {:nextjournal.clerk/visibility {:code :fold :result :hide}}))
#_(->doc-visibility '(ns foo "my docs" {}))
#_(->doc-visibility '(ns ^{:nextjournal.clerk/visibility {:code :fold :result :hide}} foo))
#_(->doc-visibility '(ns ^{:nextjournal.clerk/visibility {:code :fold}} foo
                       {:nextjournal.clerk/visibility {:result :hide}}))

(defn ->doc-settings [first-form]
  {:visibility (->doc-visibility first-form)
   :ns? (ns? first-form)
   :toc (or (#{true :collapsed} (-> first-form meta :nextjournal.clerk/toc)) false)})

#_(->doc-settings '(ns foo))
#_(->doc-settings '(ns foo {:nextjournal.clerk/visibility {:code :fold :result :hide}}))
#_(->doc-settings '(in-ns 'foo))
#_(->doc-settings '^{:nextjournal.clerk/toc true} (ns foo))
#_(->doc-settings '^{:nextjournal.clerk/toc :pin} (ns foo))
#_(->doc-settings '^{:nextjournal.clerk/toc :boom} (ns foo)) ;; TODO: error

(defn auto-resolves [ns]
  (as-> (ns-aliases ns) $
    (assoc $ :current (ns-name *ns*))
    (zipmap (keys $)
            (map ns-name (vals $)))))

#_(auto-resolves (find-ns 'rule-30))

(defn read-string [s]
  (edamame/parse-string s {:all true
                           :auto-resolve (auto-resolves (or *ns* (find-ns 'user)))
                           :readers *data-readers*
                           :read-cond :allow
                           :regex #(list `re-pattern %)
                           :features #{:clj}}))

#_(read-string "(ns rule-30 (:require [nextjournal.clerk.viewer :as v]))")

(def code-tags
  #{:deref :map :meta :list :quote :reader-macro :set :token :var :vector})

(def whitespace-on-line-tags
  #{:comment :whitespace :comma})

(defn ->codeblock [visibility node]
  (cond-> {:type :code :text (n/string node)}
    (and (not visibility) (-> node n/string read-string ns?))
    (assoc :ns? true)))

(defn parse-clojure-string
  ([s] (parse-clojure-string {} s))
  ([opts s] (parse-clojure-string opts {:blocks []} s))
  ([{:as _opts :keys [doc?]} initial-state s]
   (loop [{:as state :keys [nodes blocks visibility add-comment-on-line?]} (assoc initial-state :nodes (:children (p/parse-string-all s)))]
     (if-let [node (first nodes)]
       (recur (cond
                (code-tags (n/tag node))
                (cond-> (-> state
                            (assoc :add-comment-on-line? true)
                            (update :nodes rest)
                            (update :blocks conj (->codeblock visibility node)))
                  (not visibility)
                  (merge (-> node n/string read-string ->doc-settings)))

                (and add-comment-on-line? (whitespace-on-line-tags (n/tag node)))
                (-> state
                    (assoc :add-comment-on-line? (not (n/comment? node)))
                    (update :nodes rest)
                    (update-in [:blocks (dec (count blocks)) :text] str (-> node n/string str/trim-newline)))

                (and doc? (n/comment? node))
                (-> state
                    (assoc :add-comment-on-line? false)
                    (assoc :nodes (drop-while (some-fn n/comment? n/linebreak?) nodes))
                    (update :blocks conj {:type :markdown
                                          :doc (-> (apply str (map (comp remove-leading-semicolons n/string)
                                                                   (take-while (some-fn n/comment? n/linebreak?) nodes)))
                                                   markdown/parse
                                                   (select-keys [:type :content]))}))
                :else
                (-> state
                    (assoc :add-comment-on-line? false)
                    (update :nodes rest))))
       (merge (select-keys state [:blocks :visibility :ns?])
              (when doc?
                (-> {:content (into []
                                    (comp (filter (comp #{:markdown} :type))
                                          (mapcat (comp :content :doc)))
                                    blocks)}
                    markdown.parser/add-title+toc
                    (select-keys #{:title :toc})
                    (assoc-in [:toc :mode] (:toc state)))))))))

#_(parse-clojure-string {:doc? true} "'code ;; foo\n;; bar")
#_(parse-clojure-string "'code , ;; foo\n;; bar")
#_(parse-clojure-string "'code\n;; foo\n;; bar")
#_(keys (parse-clojure-string {:doc? true} (slurp "notebooks/viewer_api.clj")))

(defn code-cell? [{:as node :keys [type]}]
  (and (= :code type) (contains? node :info)))

(defn parse-markdown-cell [{:as state :keys [nodes]}]
  (assoc (parse-clojure-string {:doc? true} state (markdown.transform/->text (first nodes)))
         :nodes (rest nodes)
         ::md-slice []))

(defn parse-markdown-string [{:keys [doc?]} s]
  (let [{:keys [content toc title]} (markdown/parse s)]
    (loop [{:as state :keys [nodes] ::keys [md-slice]} {:blocks [] ::md-slice [] :nodes content}]
      (if-some [node (first nodes)]
        (recur
         (if (code-cell? node)
           (-> state
               (update :blocks #(cond-> % (seq md-slice) (conj {:type :markdown :doc {:type :doc :content md-slice}})))
               parse-markdown-cell)

           (-> state (update :nodes rest) (cond-> doc? (update ::md-slice conj node)))))

        (-> state
            (update :blocks #(cond-> % (seq md-slice) (conj {:type :markdown :doc {:type :doc :content md-slice}})))
            (select-keys [:blocks :visibility])
            (merge (when doc? (cond-> {:title title} (:toc state) (assoc :toc toc)))))))))

(defn parse-file
  ([file] (parse-file {} file))
  ([opts file] (-> (if (str/ends-with? file ".md")
                     (parse-markdown-string opts (slurp file))
                     (parse-clojure-string opts (slurp file)))
                   (assoc :file file))))

#_(parse-file {:doc? true} "notebooks/visibility.clj")
#_(parse-file "notebooks/visibility.clj")
#_(parse-file "notebooks/elements.clj")
#_(parse-file "notebooks/markdown.md")
#_(parse-file {:doc? true} "notebooks/rule_30.clj")
#_(parse-file "notebooks/src/demo/lib.cljc")
