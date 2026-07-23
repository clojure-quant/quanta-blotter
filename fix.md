


2. market order stresstest is not working
   - what is the difference in lmt and mkt?
     - knowing the difference is crucial
   - issue:
      OR:
      - paper broker impl (no session)
      - pull/push consolidator
      - slow blocking consumers?
        - Trading-state file logger
        - TSC / snapshot-flow

TODO:

1. fix-engine needs to support order modification.

2. run modify-order on bybit broker.

3. make a quote-pre-subscriber that subscribes for 
certain quotes. just so that we have them 
always subscribed subscribed