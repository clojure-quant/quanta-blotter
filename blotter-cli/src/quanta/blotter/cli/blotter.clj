(ns quanta.blotter.cli.blotter
  "Babashka blotter TUI built on charm.clj.

   A top-level menu switches between the orders / trades / positions pages.
   A parameter line below the menu shows page options (orders can filter
   working vs closed). Rows are fetched over the flowy websocket, rendered as
   crockery tables and paginated with charm's paginator."
  (:require
   [clojure.string :as str]
   [charm.core :as charm]
   [charm.components.paginator :as pag]
   [quanta.blotter.cli.client :as client]
   [quanta.blotter.oms.print :as print]))

(def per-page 15)

;; A crockery table is `3 separators + 1 header + N data rows`, so a full page
;; is `per-page + 4` lines. The table window is always rendered at this height
;; (padded with blank background rows) so it never changes size.
(def table-height (+ per-page 4))

(def pages [:orders :trades :positions])

(def page-title
  {:orders "ORDERS" :trades "TRADES" :positions "POSITIONS"})

(def query-fn
  {:orders 'quanta.blotter.oms.db/query-orders
   :trades 'quanta.blotter.oms.db/query-fills
   :positions 'quanta.blotter.oms.db/query-positions})

;; Only :working means the order is still open in the OMS flow.
(def working-statuses #{:working})

(defn- working? [o]
  (contains? working-statuses (:order/status o)))

(defn- position-open?
  [p]
  (if (contains? p :position/open)
    (true? (:position/open p))
    (let [q (or (:position/qty-open p) (:position/qty p))]
      (boolean (and q (not (zero? q)))))))

;; filter buttons cycle on `f`
(def order-filter-cycle {:working :closed, :closed :all, :all :working})
(def position-filter-cycle {:open :closed, :closed :all, :all :open})

;; ---------------------------------------------------------------------------
;; styling

(def page-key {:orders "1" :trades "2" :positions "3"})

(def menu-bg (charm/hex "#add8e6"))   ; light blue: the whole menu row
(def key-bg (charm/hex "#ffd43b"))    ; gold: the shortcut number cell
(def table-bg (charm/hex "#d3d3d3"))  ; light gray: behind the table

(def active-style (charm/style :fg charm/cyan :bold true))
(def error-style (charm/style :fg charm/red :bold true))
(def dim-style (charm/style :faint true))

(def filter-active-bg (charm/hex "#4dabf7"))    ; blue: selected filter box
(def filter-inactive-bg (charm/hex "#e9ecef"))  ; light gray: other filter boxes

(defn- filter-cell
  "A small button-like box for a filter option."
  [label active?]
  (charm/render
   (if active?
     (charm/style :bg filter-active-bg :fg charm/white :bold true)
     (charm/style :bg filter-inactive-bg :fg charm/black))
   (str " " label " ")))

(defn- pad-spaces [n]
  (apply str (repeat (max 0 n) \space)))

;; ---------------------------------------------------------------------------
;; row massaging (mirrors demo.db-print)

(defn- displayed-rows
  "Massage + filter + sort the raw rows for the current page."
  [{:keys [page raw-rows order-filter position-filter]}]
  (case page
    :orders (->> raw-rows
                 (filter (case order-filter
                           :working working?
                           :closed (complement working?)
                           :all (constantly true)))
                 (sort-by (comp str :order/id))
                 vec)
    :trades (->> raw-rows (sort-by :fill/date) vec)
    :positions (->> raw-rows
                    (filter (case position-filter
                              :open position-open?
                              :closed (complement position-open?)
                              :all (constantly true)))
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

(defn- menu-segment
  "Returns {:plain ... :styled ...} for one page entry: the title on the
   light-blue row background followed by its shortcut number in a contrasting
   cell."
  [state p]
  (let [active? (= p (:page state))
        label (str " " (page-title p) " ")
        k (str " " (page-key p) " ")
        sep "  "
        label-style (charm/style :bg menu-bg
                                 :fg (if active? charm/blue charm/black)
                                 :bold active?)
        key-style (charm/style :bg key-bg :fg charm/black :bold true)
        sep-style (charm/style :bg menu-bg)]
    {:plain (str label k sep)
     :styled (str (charm/render label-style label)
                  (charm/render key-style k)
                  (charm/render sep-style sep))}))

(defn- menu-line
  "The full-width light-blue menu row."
  [state width]
  (let [segs (map #(menu-segment state %) pages)
        styled (apply str (map :styled segs))
        used (reduce + (map (comp count :plain) segs))]
    (str styled
         (charm/render (charm/style :bg menu-bg) (pad-spaces (- width used))))))


(defn- params-line [state]
  (case (:page state)
    :orders (let [f (:order-filter state)]
              (str "filter: "
                   (filter-cell "working" (= :working f)) " "
                   (filter-cell "closed" (= :closed f)) " "
                   (filter-cell "all orders" (= :all f))
                   (charm/render dim-style "   (f to toggle)")))
    :positions (let [f (:position-filter state)]
                 (str "filter: "
                      (filter-cell "open" (= :open f)) " "
                      (filter-cell "closed" (= :closed f)) " "
                      (filter-cell "all positions" (= :all f))
                      (charm/render dim-style "   (f to toggle)")))
    :trades (charm/render dim-style (str (count (displayed-rows state)) " trades"))))

(defn- table-str [state]
  (let [rows (displayed-rows state)
        [start end] (pag/slice-bounds (:pager state) (count rows))
        slice (if (seq rows) (subvec rows start end) [])]
    (case (:page state)
      :orders (print/working-orders-table slice)
      :trades (print/trades-table slice)
      :positions (print/open-positions-table slice))))

(defn- gray-block
  "Render `lines` on the light-gray table background, padded to a common width
   and to `table-height` rows so the table window is always the same size,
   whether the table is full, partial or empty."
  [lines]
  (let [lines (vec lines)
        w (reduce max 0 (map count lines))
        rows (concat lines (repeat (max 0 (- table-height (count lines))) ""))
        style (charm/style :bg table-bg :fg charm/black)]
    (->> rows
         (map (fn [line] (charm/render style (str line (pad-spaces (- w (count line)))))))
         (str/join "\n"))))

(defn- body [state]
  (if (:error state)
    (gray-block (str/split (str "error: " (:error state)) #"\r?\n"))
    (gray-block (->> (str/split (table-str state) #"\r?\n")
                     (remove str/blank?)))))

(defn- view [state]
  (str (menu-line state (:width state)) "\n"
       (params-line state) "\n\n"
       (body state) "\n"
       "page " (charm/paginator-view (:pager state))
       (when (:loading? state) (charm/render dim-style "  loading\u2026")) "\n\n"
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
    [(-> state (update :order-filter order-filter-cycle) with-pager) nil]

    (and (= :positions (:page state)) (charm/key-match? msg "f"))
    [(-> state (update :position-filter position-filter-cycle) with-pager) nil]

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

    (charm/window-size? msg)
    [(assoc state :width (:width msg)) nil]

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
                   :position-filter :open
                   :raw-rows []
                   :loading? true
                   :error nil
                   :width 100
                   :pager (new-pager 1)}
                  (fetch-cmd conn :orders)])
         :update update-fn
         :view view
         :alt-screen true})
       (finally
         (client/close! conn))))))
