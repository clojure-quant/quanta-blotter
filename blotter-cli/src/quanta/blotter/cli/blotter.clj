(ns quanta.blotter.cli.blotter
  "Babashka blotter TUI built on charm.clj.

   A green status panel on the left switches between Trading (live snapshot
   stream) and History (orders / trades / positions from the DB)."
  (:require
   [clojure.string :as str]
   [charm.core :as charm]
   [charm.components.paginator :as pag]
   [quanta.blotter.oms.print :as print]
   [quanta.blotter.cli.client :as client]))

(def per-page 15)
(def table-height (+ per-page 4))
(def status-width 5)
(def trading-table-height (+ (quot table-height 2) 6))
(def trading-table-width 170)

(def history-pages [:orders :trades :positions])

(def page-title
  {:orders "ORDERS" :trades "TRADES" :positions "POSITIONS"})

(def query-fn
  {:orders 'quanta.blotter.oms.db/query-orders
   :trades 'quanta.blotter.oms.db/query-fills
   :positions 'quanta.blotter.oms.db/query-positions})

(def snapshot-fn
  'quanta.blotter.oms.flow.snapshot/trading-snapshot-fn)

(def working-statuses #{:working})

(defn- working? [o]
  (contains? working-statuses (:order/status o)))

(defn- position-open?
  [p]
  (if (contains? p :position/open)
    (true? (:position/open p))
    (let [q (or (:position/qty-open p) (:position/qty p))]
      (boolean (and q (not (zero? q)))))))

(def order-filter-cycle {:working :closed, :closed :all, :all :working})
(def position-filter-cycle {:open :closed, :closed :all, :all :open})

;; ---------------------------------------------------------------------------
;; styling

(def status-bg (charm/hex "#2b8a3e"))
(def status-active-bg (charm/hex "#1e6f31"))
(def menu-bg (charm/hex "#add8e6"))
(def key-bg (charm/hex "#ffd43b"))
(def table-bg (charm/hex "#d3d3d3"))

(def dim-style (charm/style :faint true))
(def filter-active-bg (charm/hex "#4dabf7"))
(def filter-inactive-bg (charm/hex "#e9ecef"))
(def sell-side-bg (charm/hex "#e03131"))

(def position-side-col-idx 4)
(def position-qty-open-col-idx 6)
(def order-side-col-idx 5)
(def order-qty-working-col-idx 9)

(defn- filter-cell [label active?]
  (charm/render
   (if active?
     (charm/style :bg filter-active-bg :fg charm/white :bold true)
     (charm/style :bg filter-inactive-bg :fg charm/black))
   (str " " label " ")))

(defn- pad-spaces [n]
  (apply str (repeat (max 0 n) \space)))

(defn- codepoints [^String s]
  (loop [i 0 acc []]
    (if (>= i (.length s))
      acc
      (let [cp (.codePointAt s i)]
        (recur (+ i (Character/charCount cp)) (conj acc cp))))))

(defn- char-display-width
  "Approximate terminal column width (wide emoji/symbols count as 2)."
  [^long cp]
  (cond
    (and (>= cp 0x1100)
         (or (<= cp 0x115F)
             (and (>= cp 0x2E80) (<= cp 0xA4CF))
             (and (>= cp 0xAC00) (<= cp 0xD7A3))
             (and (>= cp 0xF900) (<= cp 0xFAFF))
             (and (>= cp 0xFE10) (<= cp 0xFE19))
             (and (>= cp 0xFE30) (<= cp 0xFE6F))
             (and (>= cp 0xFF00) (<= cp 0xFF60))
             (and (>= cp 0xFFE0) (<= cp 0xFFE6)))) 2
    (and (>= cp 0x2300) (<= cp 0x23FF)) 2
    (and (>= cp 0x2600) (<= cp 0x27BF)) 2
    (and (>= cp 0x1F300) (<= cp 0x1FAFF)) 2
    :else 1))

(defn- display-width [s]
  (transduce (map char-display-width) + (codepoints s)))

(defn- pad-to-display-width [s width]
  (str s (pad-spaces (max 0 (- width (display-width s))))))

(defn- fit-line [s width]
  (let [s (if (> (count s) width) (subs s 0 width) s)]
    (str s (pad-spaces (max 0 (- width (count s)))))))

(defn- strip-ansi [s]
  (str/replace s #"\e\[[0-9;?]*[ -/]*[@-~]" ""))

(defn- pad-styled-line [s width bg-style]
  (let [visible (strip-ansi s)]
    (if (<= (count visible) width)
      (str s (charm/render bg-style (pad-spaces (- width (count visible)))))
      (subs visible 0 width))))

(defn- cell-part [text width style]
  (let [s (if (> (count text) width) (subs text 0 width) text)]
    (charm/render style (str s (pad-spaces (max 0 (- width (count s))))))))

(defn- side-badge-part [side width]
  (let [label (case side
                :buy "B"
                :sell "S"
                :long "L"
                :short "S"
                (name side))
        style (case side
                (:buy :long) (charm/style :bg status-bg :fg charm/white :bold true)
                (:sell :short) (charm/style :bg sell-side-bg :fg charm/white :bold true)
                (charm/style :bg table-bg :fg charm/black))
        n (count label)
        left (quot (- width n) 2)
        right (- width n left)]
    (charm/render style (str (pad-spaces left) label (pad-spaces right)))))

(defn- highlight-cell-part [text width]
  (cell-part text width (charm/style :bg menu-bg :fg charm/black)))

(defn- table-separator? [line]
  (str/includes? line "---"))

(defn- table-header-row? [line header-marker]
  (and (str/starts-with? line "|")
       (not (table-separator? line))
       (str/includes? line header-marker)))

(defn- table-data-row? [line header-marker]
  (and (str/starts-with? line "|")
       (not (table-separator? line))
       (not (str/includes? line header-marker))))

(defn- render-styled-table-line
  [line width {:keys [entity side-key side-col-idx highlight-col-idx]}]
  (let [line (fit-line line width)
        body-style (charm/style :bg table-bg :fg charm/black)
        styled-pipe (charm/render body-style "|")
        cells (map-indexed
               (fn [i part]
                 (cond
                   (and entity side-key (= i side-col-idx))
                   (side-badge-part (side-key entity) (count part))

                   (= i highlight-col-idx)
                   (highlight-cell-part part (count part))

                   :else (charm/render body-style part)))
               (str/split line #"\|"))]
    (reduce (fn [acc cell]
              (if acc (str acc styled-pipe cell) cell))
            nil
            cells)))

(defn- style-trading-table-lines
  [lines rows width {:keys [side-key side-col-idx highlight-col-idx header-marker]}]
  (let [rows (vec rows)]
    (loop [lines (vec lines) row-idx 0 acc []]
      (if (empty? lines)
        acc
        (let [line (first lines)
              opts {:side-key side-key
                    :side-col-idx side-col-idx
                    :highlight-col-idx highlight-col-idx}]
          (cond
            (and (table-data-row? line header-marker) (< row-idx (count rows)))
            (recur (rest lines) (inc row-idx)
                   (conj acc (render-styled-table-line line width
                                                       (assoc opts :entity (nth rows row-idx)))))

            (table-header-row? line header-marker)
            (recur (rest lines) row-idx
                   (conj acc (render-styled-table-line line width opts)))

            :else
            (recur (rest lines) row-idx (conj acc line))))))))

(defn- main-width [state]
  (max 20 (- (:width state) status-width)))

;; ---------------------------------------------------------------------------
;; history row helpers

(defn- displayed-rows
  [{:keys [history-page raw-rows order-filter position-filter]}]
  (case history-page
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

(defn- new-pager [total-pages]
  (charm/paginator :type :arabic :per-page per-page
                   :total-pages (max 1 total-pages) :page 0))

(defn- with-pager [state]
  (let [n (count (displayed-rows state))
        total (if (pos? n)
                (long (Math/ceil (/ n (double per-page))))
                1)]
    (assoc state :pager (new-pager total))))

;; ---------------------------------------------------------------------------
;; commands

(defn- fetch-cmd [conn page]
  (charm/cmd
   (fn []
     (try
       (let [rows (client/request-sync! conn (query-fn page))]
         {:type :data-loaded :page page :rows (vec rows)})
       (catch Exception e
         {:type :data-error :page page :error (ex-message e)})))))

(defn- subscribe-cmd [conn]
  (charm/cmd
   (fn []
     (let [id (client/subscribe! conn snapshot-fn)]
       {:type :subscribed :sub-id id}))))

(defn- snapshot-read-cmd [conn]
  (charm/cmd
   (fn []
     (if-let [snap (client/take-snapshot! conn 60000)]
       {:type :snapshot :data snap}
       {:type :snapshot-timeout}))))

(defn- stop-trading-sub! [state]
  (when-let [id (:sub-id state)]
    (client/unsubscribe! (:conn state) id))
  (assoc state :sub-id nil))

;; ---------------------------------------------------------------------------
;; status panel

(def mode-meta
  {:trading {:symbol "\u26a1" :hotkey "T"}
   :history {:symbol "\u231b" :hotkey "H"}})

(defn- status-line [state mode]
  (let [{:keys [symbol hotkey]} (mode-meta mode)
        active? (= mode (:mode state))
        label (str symbol hotkey)
        padded (pad-to-display-width label status-width)
        style (charm/style :bg (if active? status-active-bg status-bg)
                           :fg charm/white :bold active?)]
    (charm/render style padded)))

(defn- status-panel [state line-count]
  (let [rows [(status-line state :trading)
              (status-line state :history)]
        blank (charm/render (charm/style :bg status-bg)
                            (pad-spaces status-width))
        all (concat rows (repeat (max 0 (- line-count (count rows))) blank))]
    (vec all)))

(defn- join-panels [status-lines main-text]
  (let [main-lines (vec (str/split main-text #"\n"))
        n (max (count status-lines) (count main-lines))
        pad-main (fn [lines]
                   (into lines (repeat (- n (count lines)) "")))
        status (into status-lines (repeat (- n (count status-lines))
                                          (charm/render (charm/style :bg status-bg)
                                                        (pad-spaces status-width))))]
    (str/join "\n"
              (map (fn [s m] (str s m))
                   status (pad-main main-lines)))))

;; ---------------------------------------------------------------------------
;; table rendering

(defn- styled-block [lines height bg fg]
  (let [lines (vec (remove str/blank? lines))
        w (reduce max 0 (map count lines))
        rows (concat lines (repeat (max 0 (- height (count lines))) ""))
        style (charm/style :bg bg :fg fg)]
    (->> rows
         (take height)
         (map (fn [line]
                (charm/render style (str line (pad-spaces (- w (count line)))))))
         (str/join "\n"))))

(defn- gray-block [lines height]
  (styled-block lines height table-bg charm/black))

(defn- render-table-line [line width body-style]
  (if (not= line (strip-ansi line))
    (pad-styled-line line width body-style)
    (charm/render body-style (fit-line line width))))

(defn- trading-table-block [title n lines total-height width]
  (let [lines (vec (remove str/blank? lines))
        header-text (str title " [" n "]")
        header-style (charm/style :bg filter-active-bg :fg charm/white :bold true)
        header-line (charm/render header-style (fit-line header-text width))
        body-height (max 0 (dec total-height))
        body-lines (concat lines (repeat (max 0 (- body-height (count lines))) ""))
        body-style (charm/style :bg table-bg :fg charm/black)
        body (->> body-lines
                  (take body-height)
                  (map (fn [line]
                         (render-table-line line width body-style)))
                  (str/join "\n"))]
    (str header-line "\n" body)))

(defn- history-table-str [state]
  (let [rows (displayed-rows state)
        [start end] (pag/slice-bounds (:pager state) (count rows))
        slice (if (seq rows) (subvec rows start end) [])
        opts {:max-width (main-width state)}]
    (case (:history-page state)
      :orders (print/working-orders-table slice opts)
      :trades (print/trades-table slice opts)
      :positions (print/open-positions-table slice opts))))

(def page-key {:orders "1" :trades "2" :positions "3"})

(defn- history-menu-segment [state p]
  (let [active? (= p (:history-page state))
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

(defn- history-menu-line [state width]
  (let [segs (map #(history-menu-segment state %) history-pages)
        styled (apply str (map :styled segs))
        used (reduce + (map (comp count :plain) segs))]
    (str styled
         (charm/render (charm/style :bg menu-bg)
                       (pad-spaces (- width used))))))

(defn- history-params-line [state]
  (case (:history-page state)
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

(defn- history-body [state]
  (if (:error state)
    (gray-block (str/split (str "error: " (:error state)) #"\r?\n") table-height)
    (gray-block (str/split (history-table-str state) #"\r?\n") table-height)))

(defn- history-main [state]
  (let [w (main-width state)]
    (str (history-menu-line state w) "\n"
         (history-params-line state) "\n\n"
         (history-body state) "\n"
         "page " (charm/paginator-view (:pager state))
         (when (:loading? state) (charm/render dim-style "  loading\u2026")) "\n\n"
         (charm/render dim-style
                       "1/2/3 or tab: page    \u2190/\u2192 or h/l: paginate    f: filter    q: quit"))))

(defn- trading-main [state]
  (let [width trading-table-width
        snap (:snapshot state)
        positions (or (:open-positions snap) [])
        orders (or (:working-orders snap) [])
        pos-lines (-> (print/open-positions-table positions)
                      (str/split #"\r?\n")
                      (style-trading-table-lines positions width
                                                 {:side-key :position/side
                                                  :side-col-idx position-side-col-idx
                                                  :highlight-col-idx position-qty-open-col-idx
                                                  :header-marker "date-opened"}))
        wo-lines (-> (print/working-orders-table orders)
                     (str/split #"\r?\n")
                     (style-trading-table-lines orders width
                                                {:side-key :order/side
                                                 :side-col-idx order-side-col-idx
                                                 :highlight-col-idx order-qty-working-col-idx
                                                 :header-marker "order-id"}))]
    (str (trading-table-block "POSITIONS" (count positions) pos-lines trading-table-height width)
         "\n"
         (trading-table-block "ORDERS" (count orders) wo-lines trading-table-height width))))

(defn- view [state]
  (let [main (if (= :trading (:mode state))
               (trading-main state)
               (history-main state))
        main-lines (count (str/split main #"\n"))
        status-lines (status-panel state main-lines)]
    (join-panels status-lines main)))

;; ---------------------------------------------------------------------------
;; update

(defn- history-page-index [page]
  (.indexOf ^java.util.List history-pages page))

(defn- switch-history-page [state page]
  (assoc state
         :history-page page
         :loading? true
         :error nil
         :raw-rows []
         :pager (new-pager 1)))

(defn- goto-history-page [state page]
  (let [s (switch-history-page state page)]
    [s (fetch-cmd (:conn s) page)]))

(defn- enter-trading [state]
  [(-> state
       (assoc :mode :trading :loading? true :error nil)
       stop-trading-sub!)
   (subscribe-cmd (:conn state))])

(defn- enter-history [state page]
  (let [s (-> state
              stop-trading-sub!
              (assoc :mode :history)
              (switch-history-page page))]
    [s (fetch-cmd (:conn s) page)]))

(defn- update-fn [state msg]
  (cond
    (or (charm/key-match? msg "q") (charm/key-match? msg "ctrl+c"))
    [(stop-trading-sub! state) charm/quit-cmd]

    (or (charm/key-match? msg "t") (charm/key-match? msg "T"))
    (if (= :trading (:mode state))
      [state nil]
      (enter-trading state))

    (or (charm/key-match? msg "h") (charm/key-match? msg "H"))
    (if (= :history (:mode state))
      [state nil]
      (enter-history state (:history-page state)))

    (and (= :history (:mode state)) (charm/key-match? msg "1"))
    (goto-history-page state :orders)

    (and (= :history (:mode state)) (charm/key-match? msg "2"))
    (goto-history-page state :trades)

    (and (= :history (:mode state)) (charm/key-match? msg "3"))
    (goto-history-page state :positions)

    (and (= :history (:mode state)) (charm/key-match? msg "tab"))
    (goto-history-page state
                       (nth history-pages
                            (mod (inc (history-page-index (:history-page state)))
                                 (count history-pages))))

    (and (= :history (:mode state))
         (= :orders (:history-page state))
         (charm/key-match? msg "f"))
    [(-> state (update :order-filter order-filter-cycle) with-pager) nil]

    (and (= :history (:mode state))
         (= :positions (:history-page state))
         (charm/key-match? msg "f"))
    [(-> state (update :position-filter position-filter-cycle) with-pager) nil]

    (= :subscribed (:type msg))
    [(assoc state :sub-id (:sub-id msg))
     (snapshot-read-cmd (:conn state))]

    (= :snapshot (:type msg))
    (if (= :trading (:mode state))
      [(-> state
           (assoc :snapshot (:data msg) :loading? false :error nil))
       (snapshot-read-cmd (:conn state))]
      [state nil])

    (= :snapshot-timeout (:type msg))
    (if (= :trading (:mode state))
      [(assoc state :loading? false :error "snapshot stream timed out")
       (snapshot-read-cmd (:conn state))]
      [state nil])

    (= :data-loaded (:type msg))
    (if (= (:page msg) (:history-page state))
      [(-> state
           (assoc :raw-rows (:rows msg) :loading? false :error nil)
           with-pager)
       nil]
      [state nil])

    (= :data-error (:type msg))
    (if (= (:page msg) (:history-page state))
      [(assoc state :loading? false :error (:error msg)) nil]
      [state nil])

    (charm/window-size? msg)
    [(assoc state :width (:width msg)) nil]

    (= :history (:mode state))
    (let [[pager _] (charm/paginator-update (:pager state) msg)]
      [(assoc state :pager pager) nil])

    :else
    [state nil]))

;; ---------------------------------------------------------------------------
;; entry point

(defn start!
  "Connect to the flowy server and run the blotter TUI."
  ([] (start! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn (client/connect! ws-url)]
     (Thread/sleep 500)
     (try
       (charm/run
        {:init (fn []
                 (let [state {:conn conn
                              :mode :trading
                              :history-page :orders
                              :snapshot {:working-orders []
                                         :open-positions []}
                              :sub-id nil
                              :order-filter :working
                              :position-filter :open
                              :raw-rows []
                              :loading? true
                              :error nil
                              :width 100
                              :pager (new-pager 1)}]
                   [state (subscribe-cmd conn)]))
         :update update-fn
         :view view
         :alt-screen true})
       (finally
         (client/close! conn))))))
