(ns chlorine.repl
  (:require [clojure.string :as str]
            [repl-tooling.eval :as eval]
            [repl-tooling.repl-client.clojure :as clj-repl]
            [chlorine.state :refer [state]]
            [repl-tooling.editor-helpers :as helpers]
            [chlorine.ui.inline-results :as inline]
            [chlorine.ui.console :as console]
            [chlorine.ui.atom :as atom]))

(defn callback [output]
  (prn [:STDOUT output])
  (prn [:FOO @console/console])
  (when-let [out (:out output)]
    (some-> @console/console (.stdout out))))

(def callback-fn (atom callback))

(defn connect! [host port]
  ; FIXME: Fix this `println`
  (let [aux (clj-repl/repl :clj-aux host port println)
        primary (clj-repl/repl :clj-eval host port #(@callback-fn %))]

    (eval/evaluate aux ":done" {} #(swap! state assoc-in [:repls :clj-aux] aux))
    (eval/evaluate primary ":ok2" {} (fn []
                                       (atom/info "Clojure REPL connected" "")
                                       (swap! state
                                              #(-> %
                                                   (assoc-in [:repls :clj-eval] primary)
                                                   (assoc :connection {:host host
                                                                       :port port})))))))

(defn connect-self-hosted []
  (let [code `(do (clojure.core/require '[shadow.cljs.devtools.api])
                (shadow.cljs.devtools.api/repl :dev))
        {:keys [host port]} (:connection @state)
        repl (clj-repl/repl :clj-aux host port #(prn [:CLJS-REL %]))]

    (. (clj-repl/self-host repl code)
      (then #(do
               (swap! state assoc-in [:repls :cljs-eval] %)
               (atom/info "ClojureScript REPL connected" ""))))))

(defn set-inline-result [inline-result eval-result]
  (if-let [res (:result eval-result)]
    (inline/render-result! inline-result res)
    (inline/render-error! inline-result (:error eval-result))))

(defn need-cljs? [editor]
  (or
   (= (:eval-mode @state) :cljs)
   (and (= (:eval-mode @state) :discover)
        (str/ends-with? (.getFileName editor) ".cljs"))))

(defn- eval-cljs [editor ns-name filename row col code result callback]
  (if-let [repl (-> @state :repls :cljs-eval)]
    (eval/evaluate repl code
                   {:namespace ns-name :row row :col col :filename filename}
                   #(set-inline-result result %))
    (do
      (some-> result .destroy)
      (atom/error "REPL not connected"
                  (str "REPL not connected for ClojureScript.\n\n"
                       "You can connect a repl using "
                       "'Connect ClojureScript Socket REPL' command,"
                       "or 'Connect a self-hosted ClojureScript' command")))))

(defn evaluate-aux [editor ns-name filename row col code callback]
  (if (need-cljs? editor)
    (eval-cljs editor ns-name filename row col code nil #(-> % helpers/parse-result callback))
    (some-> @state :repls :clj-aux
            (eval/evaluate code
                           {:namespace ns-name :row row :col col :filename filename}
                           #(-> % helpers/parse-result callback)))))


(defn eval-and-present [editor ns-name filename row col code]
  (let [result (inline/new-result editor row)]
    (if (need-cljs? editor)
      (eval-cljs editor ns-name filename row col code result #(set-inline-result result %))
      (some-> @state :repls :clj-eval
              (eval/evaluate code
                             {:namespace ns-name :row row :col col :filename filename}
                             #(set-inline-result result %))))))

(defn top-level-code [editor range]
  (let [range (.. js/protoRepl -EditorUtils
                  (getCursorInBlockRange editor #js {:topLevel true}))]
    [range (some->> range (.getTextInBufferRange editor))]))

(defn ns-for [editor]
  (.. js/protoRepl -EditorUtils (findNsDeclaration editor)))

(defn- current-editor []
  (.. js/atom -workspace getActiveTextEditor))

(defn evaluate-top-block!
  ([] (evaluate-top-block! (current-editor)))
  ([editor]
   (let [range (.. js/protoRepl -EditorUtils
                   (getCursorInBlockRange editor #js {:topLevel true}))
         code (.getTextInBufferRange editor range)]
     (eval-and-present editor
                       (ns-for editor)
                       (.getFileName editor)
                       (.. range -end -row) (.. range -end -column) code))))

(defn evaluate-block!
  ([] (evaluate-block! (current-editor)))
  ([editor]
   (let [range (.. js/protoRepl -EditorUtils
                   (getCursorInBlockRange editor))
         code (.getTextInBufferRange editor range)]
     (eval-and-present editor
                       (ns-for editor)
                       (.getFileName editor)
                       (.. range -end -row) (.. range -end -column) code))))

(defn evaluate-selection!
  ([] (evaluate-selection! (current-editor)))
  ([editor]
   (let [end (.. editor getSelectedBufferRange -end)
         row (.-row end)
         col (.-column end)
         code (.getSelectedText editor)]
     (eval-and-present editor
                       (ns-for editor)
                       (.getFileName editor)
                       row col code))))