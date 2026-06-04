(ns quanta.blotter.cli.blotter
  "Babashka blotter TUI built on charm.clj.

   A top-level menu switches between the orders / trades / positions pages.
   A parameter line below the menu shows page options (orders can filter
   working vs closed). Rows are fetched over the flowy websocket, rendered as
   crockery tables and paginated with charm's paginator."
  (:require
   [clojure.string :as str]
   [clojure.set :refer [rename-keys]]
   [charm.core :as charm]
   [charm.components.paginator :as pag]
   [quanta.blotter.cli.client :as client]
   [quanta.blotter.oms.print :as print]))

(def per-page 15)

(def pages [:orders :trades :positions])

(def page-title
  {:orders "ORDERS" :trades "TRADES" :positions "POSITIONS"})

(def query-fn
  {:orders 'quanta.blotter.oms.db/query-orders
   :trades 'quanta.blotter.oms.db/query-fills
   :positions 'quanta.blotter.oms.db/query-positions})

;; Orders whose status means they are still live / working. Everything else
;; (filled, cancelled, rejected, expired, ...) counts as closed.
(def working-statuses
  #{:order/new :order/order-confirm :order/cancel-req
    :order/cancel-reject :order/fill-partial})

(defn- working? [o]
  (contains? working-statuses (:order/status o)))

;; ---------------------------------------------------------------------------
;; styling

(def active-style (charm/style :fg charm/cyan :bold true))
(def dim-style (charm/style :faint true))
(def error-style (charm/style :fg charm/red :bold true))

;; ---------------------------------------------------------------------------
;; row massaging (mirrors demo.db-print)

(defn- order-row
  "query-orders stores :order/account-id, but the print table keys on
   :order/account."
  [o]
  (rename-keys o {:order/account-id :order/account}))

(defn- displayed-rows
  "Massage + filter + sort the raw rows for the current page."
  [{:keys [page raw-rows order-filter]}]
  (case page
    :orders (->> raw-rows
                 (map order-row)
                 (filter (if (= :working order-filter) working? (complement working?)))
                 (sort-by (comp str :order/id))
                 vec)
    :trades (->> raw-rows (sort-by :fill/date) vec)
    :positions (->> raw-rows
                    (sort-by (juxt :position/account :position/asset))
                    vec)))

;; ---------------------------------------------------------------------------
;; pagination helpers

(defn- new-pager [total-pages]
  (charm/paginator :type :arabic :per-page per-page
                   :total-pages (max 1 total-pages) :page 0))

(defn- with-pager
  "Recompute the paginator from the currently displayed row count."
  [state]
  (let [n (count (displayed-rows state))
        total (if (pos? n)
                (long (Math/ceil (/ n (double per-page))))
                1)]
    (assoc state :pager (new-pager total))))

;; ---------------------------------------------------------------------------
;; data fetching commands

(defn- fetch-cmd [conn page]
  (charm/cmd
   (fn []
     (try
       (let [rows (client/request-sync! conn (query-fn page))]
         {:type :data-loaded :page page :rows (vec rows)})
       (catch Exception e
         {:type :data-error :page page :error (ex-message e)})))))

;; ---------------------------------------------------------------------------
;; view

(defn- menu-line [state]
  (->> pages
       (map (fn [p]
              (if (= p (:page state))
                (charm/render active-style (str "[" (page-title p) "]"))
                (charm/render dim-style (str " " (page-title p) " ")))))
       (str/join "  ")))

(defn- params-line [state]
  (case (:page state)
    :orders (let [working? (= :working (:order-filter state))]
              (str "filter: "
                   (charm/render (if working? active-style dim-style) "working")
                   (charm/render dim-style " | ")
                   (charm/render (if working? dim-style active-style) "closed")
                   (charm/render dim-style "   (f to toggle)")))
    :trades (charm/render dim-style (str (count (displayed-rows state)) " trades"))
    :positions (charm/render dim-style (str (count (displayed-rows state)) " positions"))))

(defn- table-str [state]
  (let [rows (displayed-rows state)
        [start end] (pag/slice-bounds (:pager state) (count rows))
        slice (if (seq rows) (subvec rows start end) [])]
    (case (:page state)
      :orders (print/working-orders-table slice)
      :trades (print/trades-table slice)
      :positions (print/open-positions-table slice))))

(defn- body [state]
  (cond
    (:error state) (charm/render error-style (str "error: " (:error state)))
    (:loading? state) "loading..."
    (empty? (displayed-rows state)) (charm/render dim-style "(no rows)")
    :else (table-str state)))

(defn- view [state]
  (str (menu-line state) "\n"
       (params-line state) "\n\n"
       (body state) "\n"
       "page " (charm/paginator-view (:pager state)) "\n\n"
       (charm/render dim-style
                     "1/2/3 or tab: page    \u2190/\u2192 or h/l: paginate    f: filter    q: quit")))

;; ---------------------------------------------------------------------------
;; update

(defn- page-index [page]
  (.indexOf ^java.util.List pages page))

(defn- switch-page [state page]
  (assoc state
         :page page
         :loading? true
         :error nil
         :raw-rows []
         :pager (new-pager 1)))

(defn- goto [state page]
  (let [s (switch-page state page)]
    [s (fetch-cmd (:conn s) page)]))

(defn- update-fn [state msg]
  (cond
    (or (charm/key-match? msg "q") (charm/key-match? msg "ctrl+c"))
    [state charm/quit-cmd]

    (charm/key-match? msg "1") (goto state :orders)
    (charm/key-match? msg "2") (goto state :trades)
    (charm/key-match? msg "3") (goto state :positions)

    (charm/key-match? msg "tab")
    (goto state (nth pages (mod (inc (page-index (:page state))) (count pages))))

    (and (= :orders (:page state)) (charm/key-match? msg "f"))
    [(-> state
         (update :order-filter {:working :closed :closed :working})
         with-pager)
     nil]

    (= :data-loaded (:type msg))
    (if (= (:page msg) (:page state))
      [(-> state
           (assoc :raw-rows (:rows msg) :loading? false :error nil)
           with-pager)
       nil]
      [state nil])

    (= :data-error (:type msg))
    (if (= (:page msg) (:page state))
      [(assoc state :loading? false :error (:error msg)) nil]
      [state nil])

    :else
    (let [[pager _] (charm/paginator-update (:pager state) msg)]
      [(assoc state :pager pager) nil])))

;; ---------------------------------------------------------------------------
;; entry point

(defn start!
  "Connect to the flowy server and run the blotter TUI."
  ([] (start! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn (client/connect! ws-url)]
     ;; give the websocket a moment to finish the handshake before the first
     ;; request goes out.
     (Thread/sleep 500)
     (try
       (charm/run
        {:init (fn []
                 [{:conn conn
                   :page :orders
                   :order-filter :working
                   :raw-rows []
                   :loading? true
                   :error nil
                   :pager (new-pager 1)}
                  (fetch-cmd conn :orders)])
         :update update-fn
         :view view
         :alt-screen true})
       (finally
         (client/close! conn))))))
