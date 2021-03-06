(ns epsilon.client
  (:require [reagent.core :as reagent]
            [reagent.ratom :as ratom :refer [reaction]]
            [ajax.core :as ajax :refer [GET POST ajax-request]]
            [day8.re-frame.http-fx]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [epsilon.hiccup :as hiccup]
            [epsilon.icons.calendar]
            [epsilon.icons.cloud-lightning]
            [epsilon.icons.chevrons-left]
            [epsilon.icons.delete]
            [epsilon.icons.refresh-cw]
            [epsilon.icons.search]
            [epsilon.icons.tag]
            [epsilon.icons.user]
            [epsilon.logo]

            ))

;; some day, my son, all this will be user-selectable
(def preferred-multipart-alternative "text/html")


(defn inner-html [s]
  {:dangerouslySetInnerHTML {:__html s}})

(defn html-entity
  ([name attrs]
   [:span (merge attrs (inner-html (str name ";")))])
  ([name] (html-entity name {})))


(defn merge-attrs [[name attrs & kids] more-attrs]
  (let [attrs (merge attrs more-attrs)
        vb (if-let [overridden (get attrs :view-box)]
             (str/join " " overridden)
             (get :viewBox attrs))
        attrs (assoc attrs :viewBox vb)]
    (into [name attrs] kids)))

;; -- Domino 1 - Event Dispatch -----------------------------------------------


;; -- Domino 2 - Event Handlers -----------------------------------------------

(rf/reg-event-db
  :initialize
  (fn [_ _]
    {:search-term "tag:inbox"
     :search-widget "tag:inbox"
     :suggestions []
     }))

(rf/reg-event-fx
 :search-from-widget
 (fn [{:keys [db]} [_]]
   {:db (assoc db :search-term (:search-widget db))
    }))



(rf/reg-event-fx
 :search-widget-updated
 (fn [{:keys [db]} [_ term]]
   {:db (assoc db :search-widget term)}))

(rf/reg-event-fx
 :tag-filter-widget-updated
 (fn [{:keys [db]} [_ term]]
   {:db (assoc db :tag-filter-widget term)}))

(rf/reg-event-fx
 :search-term-zapped
 (fn [{:keys [db]} [_ term]]
   {:db (assoc db :search-widget "" :search-term "")}))


(defn ajax-cancel [rq]
#_  (println "cancel ajax request (ignoring) " rq))

(rf/reg-event-db
 :show-suggestions
 (fn [db [_ val]]
   (assoc db :show-suggestions val)))

(rf/reg-event-fx
 :view-thread-requested
 (fn [{:keys [db]} [_ thread-id]]
   {:db (assoc db :thread-id thread-id)
    :http-xhrio {:method          :get
                 :uri             "/show"
                 :params 	  {:id thread-id}
                 :format          (ajax/url-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:thread-retrieved]
                 :on-failure      [:thread-retrieve-failed]}}))

(rf/reg-event-fx
 :set-tag
 (fn [{:keys [db]} [_ message-id tag add?]]
   (let [m (get-in db [:messages message-id])
         present? (get-in db [:messages message-id :tags tag])
         r {:uri  (str "/messages/" (js/encodeURIComponent (:id m)) "/tags/" tag)
            :format (ajax/url-request-format)
            :response-format (ajax/json-response-format {:keywords? true})
            :on-success      [:xhr-succeeded]
            :on-failure      [:xhr-failed]}]
     (cond (and add? (not present?))
           {:db (update-in db [:messages message-id :tags] conj tag)
            :http-xhrio (assoc r :method :put)}
           (and (not add?) present?)
           {:db (update-in db [:messages message-id :tags] disj tag)
            :http-xhrio (assoc r :method :delete)}))))

(rf/reg-event-db
 :xhr-succeeded
 (fn [db [_ result]]
   (println result)
   db))

(rf/reg-event-db
 :xhr-failed
 (fn [db [_ result]]
   (println "failed" result)
   db))

(defn unnest-thread [flattened [frst & replies]]
  (let [flattened (conj flattened frst)]
    (reduce (fn [a r] (unnest-thread a r)) flattened (first replies))))

(rf/reg-event-db
 :thread-retrieved
 (fn [db [_ result]]
   (if result
     (let [flattened (unnest-thread [] (-> result first first))
           add-tag-info (fn [m]
                          (assoc m :editing-tags false :tags (set (:tags m))))]
       (assoc db
              :messages (reduce (fn [a m] (assoc a (:id m) (add-tag-info m)))
                                {}
                                flattened)
              :thread (map :id flattened)))
     (assoc db :messages {} :thread nil))))

(rf/reg-event-fx
  :toggle-editing-tags
  (fn [{:keys [db]} [_ id]]
    {:db (update-in (assoc db :tag-filter-widget "")
                    [:messages id :editing-tags] not)}))

(rf/reg-event-db
 :thread-retrieve-failed
 (fn [db [_ error]]
   (.log js/console error)
   (assoc db :error error)))

(rf/reg-event-db
 :http-error
 (fn [db [_ error]]
   (assoc db :http-pending false :http-error error)))

(rf/reg-event-db
 :http-pending
 (fn [db [_ pending? ]]
   (assoc db :http-pending pending? :http-error nil)))


;; -- Domino 4 - Query  -------------------------------------------------------

;;; basic accessors

(rf/reg-sub
  :search-term
  (fn [db _]
    (:search-term db)))

(rf/reg-sub
  :search-widget
  (fn [db _]
    (:search-widget db)))

(rf/reg-sub
  :tag-filter-widget
  (fn [db _]
    (:tag-filter-widget db)))

(rf/reg-sub
  :show-suggestions
  (fn [db _]
    (:show-suggestions db)))


(rf/reg-sub
 :thread-message-ids
 (fn [db _]
   (:thread db)))

(rf/reg-sub
 :message
 (fn [db [_ id]]
   (get (:messages db) id)))

(rf/reg-sub
 :thread-subject
 (fn [db _]
   (:Subject (:headers (first (:thread db))))))

(rf/reg-sub
 :http-error
 (fn [db _] (:http-error db)))

(rf/reg-sub
 :http-pending
 (fn [db _] (:http-pending db)))

;;; external sources

;; Doing XHR in re-frame without event ping-pong.  I not sure if this
;; is a good idea, mostly because surely if it was, someone would have
;; written it up already?

;; Basically, this is a reaction that watches :search-term, triggers
;; a server lookup, and provides changes to its subscriber whenever
;; the server comes back.  It doesn't update anything in the app-db, because
;; I'm viewing the server lookup as a computation (albeit a distributed one)
;; and the app-db is for source data, not for the result of
;; computations on that data.  It uses run! because ajax-request is async and
;; the server message comes in a callback not a return value.

(defn ajax [opts]
  (let [h (:success-handler opts)]
    (println  "pending")
    (rf/dispatch [:http-pending true])
    (ajax-request
     (assoc (dissoc opts :success-handler)
            :handler (fn [[ok? r]]
                       (println  "not")
                       (rf/dispatch [:http-pending false])
                       (if ok?
                         (h r)
                         (rf/dispatch [:http-error r])))))))

(defn get-search-results-from-server [term handler]
  (ajax
    {:method          :get
     :uri             "/search"
     :timeout 5000
     :params 	      {:q term :limit 25}
     :format          (ajax/url-request-format)
     :response-format (ajax/json-response-format {:keywords? true})
     :success-handler handler}))

(rf/reg-sub-raw
  :search-result
  (fn [db _]
    (let [value (reagent/atom [])]
      (ratom/run!
       (let [term @(rf/subscribe [:search-term])]
         (when-not (str/blank? term)
           (reset! value [])
           (println "here we go again" (pr-str term))
           (get-search-results-from-server term #(reset! value %)))))
      (reaction @value))))

;; suggestions handler subscribes to :search-widget, when it changes, fires
;; xhr to /completions and updates a ratom when the results arrive

(defn get-suggestions-from-server [opts]
  (ajax
   (merge
    {:method          :get
     :timeout 5000
     :uri             "/completions"
     :format          (ajax/url-request-format)
     :response-format (ajax/json-response-format {:keywords? true})}
    opts)))

(rf/reg-sub-raw
 :suggestions
 (fn [app-db _]
   (let [value (reagent/atom [])]
     (ratom/run!
      (let [term @(rf/subscribe [:search-widget])]
        (reset! value [])
        (get-suggestions-from-server
         {:params {:q term :limit 10}
          :success-handler #(reset! value %)})))
     (reaction @value))))


;; similar but just tags - this is for the tag editor popup

(rf/reg-sub-raw
 :tags
 (fn [app-db _]
   (let [value (reagent/atom [])]
     (ratom/run!
      (get-suggestions-from-server
       {:params {:q "tag:" :limit 50}
        :success-handler #(reset! value %)}))
     (reaction @value))))


;;; derived queries

(rf/reg-sub
 :tag-names
 (fn [query-v _]
   [(rf/subscribe [:tag-filter-widget])
    (rf/subscribe [:tags])])
 (fn [[prefix tags] _]
   (filter #(str/starts-with? % (or prefix "")) (map second tags))))


;; -- Domino 5 - View Functions ----------------------------------------------

(defn search-term-input
  []
  (let [term @(rf/subscribe [:search-widget])]
    [:div.search-term-input
     [:form {:on-submit
             (fn [e]
               (rf/dispatch [:search-from-widget])
               (.stopPropagation e)
               (.preventDefault e))}
      [:div.widget ;{:style {:position "relative"}}
       [:input {:type "text"
                :placeholder "Search messages"
                :auto-complete "off"
                :value term
                :on-change #(rf/dispatch [:search-widget-updated (-> % .-target .-value)])
                }]
       [:span {:style {:position "absolute" :top "4px" :right "0.2em"}}
        (merge-attrs
         epsilon.icons.search/svg
         {:style {:color "grey"} :view-box [2 2 24 24] :height 30 :width 30})]]
      [:span {:on-click #(rf/dispatch [:search-widget-updated ""])}
       (html-entity "&nbsp") (html-entity "&nbsp")
       (merge-attrs epsilon.icons.delete/svg {:view-box [0 2 30 20] :height 40 :width 34})
       (html-entity "&nbsp")
       ]]]))

(defmulti html-for-suggestion (fn [[key value]] key))
(defmethod html-for-suggestion "address" [[key value]]
  [:span
   (merge epsilon.icons.user/svg {:width 18 :height 18}) value])
(defmethod html-for-suggestion "tag" [[key value]]
  [:span
   (merge epsilon.icons.tag/svg {:width 18 :height 18}) value])

(defmethod html-for-suggestion "date" [[key value]]
  [:span
   (merge epsilon.icons.calendar/svg {}) value])

(defmethod html-for-suggestion :default [[key value]]
  value)

(defn urlable-term [term]
  (if (string? (first term))
    (str (get {"address" "from"} (first term) (first term)) ":" (second term))
    term))

(defn suggestions
  []
  [:div.taglist
   (into [:ul]
         (map (fn [suggestion]
                [:li
                 {:key suggestion
                  :on-click
                  (fn [e]
                    (rf/dispatch [:show-suggestions false])
                    (rf/dispatch [:search-widget-updated (urlable-term suggestion)])
                    (rf/dispatch [:search-from-widget]))
                  }
                 (html-for-suggestion suggestion)])
              @(rf/subscribe [:suggestions])))])


(defn el-for-tag [text]
  [:div.tag {} [:span.angle] [:span.name [:span text]]])

(defn search-result
  []
  (let [rs @(rf/subscribe [:search-result])]
    [:div.search-results
     (map (fn [r]
            [(if (some #{"unread"} (:tags r)) :div.result.unread :div.result)
             {:key (:thread r)
              :on-click #(rf/dispatch [:view-thread-requested (:thread r)])}
             [:div.subject
              [:span.subject (:subject r)]
              (into [:span.tags.threadview] (map el-for-tag (:tags r)))]
             [:div
              [:span.authors (:authors r)]
              [:span.when [:div (:date_relative r)]]]])
          rs)]))

(defmulti render-message-part (fn [message part] (:content-type part)))

(defmethod render-message-part "multipart/alternative" [m p]
  (let [c (:content p)
        preferred (first (filter #(= (:content-type %) preferred-multipart-alternative) c))]
    (render-message-part m preferred)))

(defmethod render-message-part "multipart/mixed" [m p]
  (let [c (:content p)]
    (into [:div] (map (partial render-message-part m) c))))

(defmethod render-message-part "text/plain" [m p]
  [:div {} [:pre (:content p)]])

;; FIXME Probably we should do HTML parsing when the message is fetched, not every time
;; we render it. On the other hand, presumably we will only call this method when
;; the underlying data has changed

(defmethod render-message-part "text/html" [m p]
  (let [[_ attrs & content]  (hiccup/parse-html (:content p))]
    (into [:div attrs] content)))

(defn download-link [part message-id]
  [:div.attachment {:key (:id part)}
   [:a {:href (str "/raw?"
                   "id=" message-id "&"
                   "content-type=" (:content-type part) "&"
                   "part=" (:id part))
        :target "_download"} "Download " (:content-type part) " attachment " (:filename part)]])

(defmethod render-message-part "application/octet-stream" [m p]
  [download-link p (:id m)])

(defmethod render-message-part "application/pdf" [m p]
  [download-link p (:id m)])

(defmethod render-message-part "multipart/related" [m p]
  (when-let [s (:start p)]
    (println "ignoring start param " s))
  (render-message-part m (first (:content p))))

(defmethod render-message-part "multipart/signed" [m p]
  ;; The multipart/signed content type contains exactly two body parts.
  ;; The first body part is the body part over which the digital signature
  ;; was created, including its MIME headers.  The second body part
  ;; contains the control information necessary to verify the digital
  ;; signature.   - RFC 1847
  (render-message-part m (first (:content p))))

(defmethod render-message-part :default [m p]
  [:div {:key (:id p)} [:pre "mime type " (:content-type p) " not supported"]])

(def r-arrow (html-entity "&rarr"))

;; XXX this is not 100% according to rfc 282x or whatever it is now.  More like 30%
(defn parse-email-address [address]
  (if address
    (let [angles-re #"<([^\>]*)>"
          parens-re #"\([^\)]*\)"]
      (if-let [addr-spec (second (re-find angles-re address))]
        {:addr-spec addr-spec
         :name (str/trim (str/replace address angles-re ""))}
        (if-let [name (second (re-find parens-re address))]
          {:addr-spec (str/trim (str/replace address parens-re ""))
           :name name}
          {:addr-spec address :name address})))))

(defn el-for-email-address [address]
  (let [parsed (or (parse-email-address address) {})]
    (if (empty? (:addr-spec parsed))
      [:span "(none)"]
      [:a.email-address
       {:href (str "mailto:" (:addr-spec parsed))
        :title address}
       (:name parsed)])))

(defn tag-editor-popup [m]
  [:div {:style {:position "relative"}}
   [:div {:on-click  #(rf/dispatch [:toggle-editing-tags (:id m)])
          :style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                  :background-color "rgba(50,50,50,0.15)"
                  :z-index 10000}}]
   [:div {:style {:background "#ded"
                  :top "10px"
                  :left "10px"
                  :z-index 20001
                  :position "absolute"}}
    [:div {:style {:background-color "#aca"}} "Set tags"]
    (into
     [:ul {:style {:margin "0px" :padding "12px"
                   :height "50vh"
                   :font-size "110%"
                   :overflow-y "scroll"
                   :list-style-type "none"}}
      [:li {:style {:margin "6px 0"}}
       [:input
        {:style {:width "10em"}
         :placeholder "Start typing"
         :on-change #(rf/dispatch [:tag-filter-widget-updated (-> % .-target .-value)])
         :onBlur #(println (-> % .-target .-value))}]]
      (let [p @(rf/subscribe [:tag-filter-widget])]
        (if-not (str/blank? p)
          [:li {:style {:font-size "90%"}
                :on-click #(do (rf/dispatch [:set-tag (:id m) p true])
                               (rf/dispatch [:tag-filter-widget-updated ""]))}
           [:i "new tag " [:b p]]]))]
     (map (fn [tag]
            (let [present? ((:tags m) tag)]
              [:li {:style {:position "relative"}
                    :on-click #(rf/dispatch [:set-tag (:id m) tag (not present?)])}
               [:div {:style {:display "inline-block"
                              :width "1em"}}
                (html-entity (if present? "&#10004" "&nbsp"))]
               tag]))
          @(rf/subscribe [:tag-names])))]])

(defn render-message [message-id]
  (let [m @(rf/subscribe [:message message-id])]
    [:div.message {:key (:id m)
                   :data-mid (:id m)}
     (let [h (:headers m)]
       [:div.headers.compact-headers
        [:div
         (el-for-email-address (:From h))
         " " r-arrow " "
         (el-for-email-address (:To h))]
        [:div (:Date h)]
        (into [:div.tags.headers
               {:on-click #(rf/dispatch [:toggle-editing-tags message-id])}]
              (conj (map el-for-tag (:tags m))
                    (el-for-tag [:i "+ add tag"])))
        (if (:editing-tags m)
          [tag-editor-popup m])])
     (into [:div.message-body {}] (map (partial render-message-part m) (:body m)))]))

(defn render-thread [message-ids]
  (into [:div.thread {}]
        (map #(vector render-message %1 %2) message-ids (range))))

;; XXX this may change?  e.g. if desktop user resizes window, or mobile user
;; rotates the device
(def viewport-size
  [(or (.-innerWidth js/window) (.. js/document documentElement clientWidth))
   (or (.-innerHeight js/window) (.. js/document documentElement clientHeight))])

(defn bounding-rect [el]
  (let [bounding (.getBoundingClientRect el)]
    [(.-left bounding) (.-top bounding)
     (.-right bounding) (.-bottom bounding)]))

(defn in-viewport? [el]
  (let [[_ top _ bottom] (bounding-rect el)
        [_ height] viewport-size]
    (or
     (and (> top 0) (< bottom height)) ; within viewport
     (and (< top 0) (> bottom 0))      ; crosses upper edge of viewport
     (and (< top height) (> bottom height)) ; crosses lower edge of viewport
     )))


(defn thread-pane
  []
  (render-thread @(rf/subscribe [:thread-message-ids])))

(defn menu [title & items]
  [:div.titleblock {}
   (into [:div.menu {}] items)
   title
   (and @(rf/subscribe [:http-pending])
        [:div {:style {:position "fixed" :right "8px" :top "8px"}}
         (merge-attrs
          epsilon.icons.cloud-lightning/svg
          {:style {:color "grey"} :view-box [0 0 24 24] :height 20 :width 20})])])


(defn search-page []
  [:div
   (menu [:span.title "Epsilon"]
         [:div.item.clickable
          {:key :home :on-click #(rf/dispatch [:search-term-zapped])}
          (merge-attrs epsilon.logo/svg {:width 30 :height 30})]
         [:div.item.clickable
          {:key :refresh
           :on-click
           #(rf/dispatch
             [:search-from-widget])}
          (merge-attrs epsilon.icons.refresh-cw.svg {:view-box [0 0 25 25] :width 30 :height 30})])
   [:div.content
    [:div.search
     {:on-focus #(rf/dispatch [:show-suggestions true])
      :tabIndex -1
      :on-blur #(do
                  (rf/dispatch [:show-suggestions false])
                  (rf/dispatch [:search-from-widget])
                  (println "blur"))}
     [search-term-input]
     (if @(rf/subscribe [:show-suggestions]) [suggestions])]
    [:div {:id "threads"}
     [search-result]]]])

(defn remove-unread-marks [thread-el]
  (let [message-els (.getElementsByClassName thread-el "message")
        visible-els (filter in-viewport? (array-seq message-els))]
    (run! #(rf/dispatch [:set-tag (.. % -dataset -mid) "unread" false])
          visible-els)))


(defn div-thread-content []
  (reagent/create-class
   {:component-did-mount (fn [c] (remove-unread-marks (reagent/dom-node c)))
    :reagent-render
    (fn []
      [:div.thread.content
       {:on-scroll #(remove-unread-marks (.-target %))}
       [thread-pane]])}))

(defn thread-page []
  [:div
   (menu
    [:span {:style {:font-size "16px" :vertical-align :top
                    :display "inline-block"
                    :padding-top "4px"
                    :padding-left "22px"
                    :margin-top "4px" }}
     @(rf/subscribe [:thread-subject])]
    [:div.item.clickable {:key :back :on-click #(rf/dispatch [:thread-retrieved nil])}
     (merge-attrs epsilon.icons.chevrons-left/svg
                  {:view-box [5 4 18 18] :width 30 :height 30})])
   [div-thread-content]
   ])


(defn ui
  []
  [:div
   (if-let [err @(rf/subscribe [:http-error])]
     [:div {:style {:position :fixed
                    :bottom "3vh" :left "3vw"
                    :width "88vw"
                    :background-color "#ee4444"
                    :color "white"
                    :z-index 99999
                    :padding "6px 6px 5px 12px"
                    :font-height "13px"
                    :box-shadow "3px 3px 6px black"
                    }}
      "ERROR: (" (:status err) ") " (:status-text err)])
   (if @(rf/subscribe [:thread-message-ids])
     [thread-page]
     [search-page])])



;; -- Entry Point -------------------------------------------------------------

(defn ^:export run
  []
  (rf/dispatch-sync [:initialize])     ;; puts a value into application state
  (rf/dispatch-sync [:search-widget-updated "tag:inbox"])
  (rf/dispatch-sync [:search-from-widget])
;  (rf/dispatch [:view-thread-requested "0000000000007495"])
  (reagent/render [ui]              ;; mount the application's ui into '<div id="app" />'
                  (js/document.getElementById "app")))
