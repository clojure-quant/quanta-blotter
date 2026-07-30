


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

