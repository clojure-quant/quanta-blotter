# STRESSTEST


Stresstest Performance Limit Buy/Sell


                                   quote tick  quote tick 
scenario                           250         100

- get quote (near-limit-order)   250           100    
- send quote 1
- wait for quote (fill 1)        250           100
- quote for fill 2               250           100
- wait between fills               0             0
  (quote needs more than 100ms)
- total                          750          300

one order                        750          300
two order                       1500          600  
reality:                        1500