# quanta-blotter [![GitHub Actions status |clojure-quant/quanta-blotter](https://github.com/clojure-quant/quanta-blotter/workflows/CI/badge.svg)](https://github.com/clojure-quant/quanta-blotter/actions?workflow=CI)[![Clojars Project](https://img.shields.io/clojars/v/io.github.clojure-quant/quanta-blotter.svg)](https://clojars.org/io.github.clojure-quant/quanta-blotter)


[Clojars Project](https://clojars.org/io.github.clojure-quant/quanta-blotter-cli)

- quote manager and 
- order management system
  - multiple accounts
  - multiple traders
  - working order and open position view
  - backoffice (datahike db)
  - multiple apis (bybit and fix in other clojure-quant artefacts)
  
## QUOTES

*quote-stream-print* starts a quote account manager, prints live quotes as they arrive,
and cycles FX subscriptions over time (add/remove pairs every ~7s) to demo
streaming quotes and dynamic subscription changes.
```
cd demo
clj -X:quote-stream-print
```

*quote-stream-mixer* demos the `quotes` API: opens per-asset quote flows (EURUSD, USDJPY, EURNOK),
prints one stream alone and a mixed stream of all three, then stops the mixed printer after 5s.
```
cd demo
clj -X:quote-stream-mixer
```

*quote-asset-list-print* will print a table of quotes to the terminal.
the :list parameter comes from asset-lists from the database.
default list has assets from bybit/ctrader-fix/random.
if no list is specified, then the asset-lists are changed every 7 seconds.
```
cd demo
clojure -X:quote-asset-list-print :list '"crypto"'
clojure -X:quote-asset-list-print :list '"spot-fx"'
clojure -X:quote-asset-list-print :list '"test"'
clojure -X:quote-asset-list-print :list '"default"'
clojure -X:quote-asset-list-print

```

*quote-snapshot-print* takes one quote-snapshot per source (random, spot-fx FIX, crypto)
and prints each result, then exits.
```
cd demo
clj -X:quote-snapshot-print
```

*quote-snapshot-performance* times sequential (default) or parallel quote-snapshots for a fixed set of assets.
`:repeat` controls how many times each unique asset is requested (default 5).
```
cd demo
clj -X:quote-snapshot-performance
clj -X:quote-snapshot-performance :parallel true
clj -X:quote-snapshot-performance :parallel true :repeat 3
```

## OMS

### STRESS TESTS
```
  cd demo
  clj -X:stresstest :account-id 1
```

### OMS Server

```
cd demo
clj -X:cli-server

in other terminal
bb tasks                (to find out which tasks are available)
bb client
bb send-orders fx1
bb send-orders qqq

```






### MESSAGE TYPES

- trader/new-order              place a new order
- trader/cancel-order           cancel existing order
trader/modify-order           modify existing order
trader/position-status
- broker/order-rejected         a new order is rejected
- broker/order-confirmed        a new order is confirmed (so it is working)
[broker/order-pending-new]    order received but not working yet. avoid if possible
- broker/order-canceled         an existing order is canceled (so it is no longer working)
broker/order-cancel-reject    cancel of existing order rejected (see :text for reason)
broker/order-modified         the order was modified (as per trader request)
- broker/order-filled           a fill or a partial fill "execution-report"
broker/order-expired          for example good-till-day order or fill-or-kill order 
broker/order-status           this is the order-status of the broker. avoid if possible. (will not get granular fills)
- broker/message
broker/session-message
broker/position-status
broker/balance-status
broker/margin-status

session/connected
session/disconnected

oms/order-status                 internally generated
oms/position-status              internally generated 


## NOTES

- to use fill partial/complete for a broker that supports it is ok.
however, if a broke does only send fill, then what does this mean?
it means the broker-api needs to track if an order is open or not.
this adds additional complexity to the broker-api.
therefore the OMS needs to accept multiple fillpartial, and needs
to gracefully interpret the last fillpartial as fillcomplete.
- we might be able to skip cancelacq and only go for cancel.


