


notes 

- goal is to have asap a prototype that can do the trades onto demo accounts
  if something goes wrong there, then it can be fixed without a damage.

- for stage 1 we do some hacks:
  - gateway-accounts.json (stored on each gateway)
    - each gateway has a json file with accounts it will accept.
    - so mt4 gateway will have all mt4 demo accounts
    - my gateway will have a config of ctrader/paper/bybit demo accounts
  - gateways.json
      [{:gateway-id 1 :gateway-name "mt4 bridge" :ip "0.0.0.0"}
       {:gateway-id 2 :gateway-name "ctrader/paper/fix/bybit bridge" :ip "0.0.0.0"}]  
  - trader-accounts.json 
    - [{:account-id 1 :trader-id 1 :gateway-id 1}
       {:account-id 2 :trader-id 1 :gateway-id 1}
       {:account-id 3 :trader-id 1 :gateway-id 1}
       {:account-id 4 :trader-id 1 :gateway-id 2}
       {:account-id 5 :trader-id 2 :gateway-id 2}]

- derived goals
  - the messaging protocol of in/out queue of the broker gateways should stabilize. 
    this is not only the payload of messages that are sent, but also what is expected
    to get sent (example: either order ack or order reject after a new order).
    once this is stabilized, then the 


- I will do for now:
  - paper trading account
  - ctrader fix api
  - ctrader native api
  - bybit websocket api