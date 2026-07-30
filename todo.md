


trading state needs to calculate fills/positions/orders in one go.



market order stresstest is not working
   - what is the difference in lmt and mkt?
     - knowing the difference is crucial
   - issue:
      OR:
      - paper broker impl (no session)
      - pull/push consolidator
      - slow blocking consumers?
        - Trading-state file logger
        - TSC / snapshot-flow




DB TRANSACTOR NOW DOES NOT SHUTDOWN CORRECTLY.



SQID 
https://github.com/sqids/sqids-clojure
to show db ids as shorter ids.

ACTOR
- Akka / Apache Pekko — supervision hierarchies, clustering, persistence, or a mature distributed actor platform.
  Pekko is the open-source continuation of Akka after the license change.
  https://github.com/lotuc/akka-clojure/tree/master#use-cases

- pulsar https://docs.paralleluniverse.co/pulsar/ https://github.com/puniverse/pulsar
  actors with prioritizing inbox. 


GRAPH builder
https://github.com/mpdairy/posh/blob/master/src/posh/lib/graph.cljc
https://github.com/mpdairy/posh/blob/master/src/posh/lib/update.cljc
POSH erlaubt watchen von transactor, und updated die queries.

CLOJURE explicit binding
Instead of using :keys, you can specify every binding yourself.
(let [{h :handler
       p :port} m]
  ...)
  (let [{port :env.long/PORT
       :or {port 8080}} m]
  port)

QUANTCONNECT LEAN https://www.lean.io/ 20000 stars
   https://github.com/QuantConnect/Lean
   https://github.com/QuantConnect/Lean.Brokerages.InteractiveBrokers

  NautilusTrader 2500 stars
  https://github.com/nautechsystems/nautilus_trader/

INTERACTIVE BROKERS QUANTCONNECT
ib server connection interrupted, TCP socket still alive
IB reports connectivity changes through error/status codes:
IB code	Meaning
1100	Connectivity between TWS/Gateway and IB servers was lost
1101	Connectivity restored, but market-data subscriptions were lost
1102	Connectivity restored and subscriptions were maintained



I see 2026-07-30T01:24:17.900Z nixnuc1 ERROR [quanta.blotter.oms.validation.channel:70] - {:original-msg {:order-type :limit, :date #time/instant "2026-07-30T01:24:17.850Z", :limit 1.13394M, :account/id 1000, :type :broker/order-confirmed, :order-id "CH8y64", :position-id "234196014", :side :buy, :qty 10000M, :asset "EURUSD"}, :schema/error "{:date [\"must be a java.util.Date\"]}", :direction :orderupdate}
2026-07-30T01:24:17.905Z nixnuc1 INFO [quanta.blotter.consolidator:50] - consolidater sending: {:type :broker/orderupdate-schema-error, :date #time/instant "2026-07-30T01:24:17.850Z", :message "spec-error {:date [\"must be a java.util.Date\"]}", :account/id 1000, :order-id "CH8y64"}

so from somewhere we get tick/instant and not tick/inst.
I believe it comes from the fix-engine dependency.
this is on my local dist /home/florian/repo/clojure/quanta/fix-engine.