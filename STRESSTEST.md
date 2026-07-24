# STRESSTEST


## Quote Stresstest

### Bybit Quote Subscription Latency.

| account | asset | op | delay |
|--------:|-------|----|------:|
| 2 | BTCUSDT.LF.BB | subscribe | 346 ms |
| 2 | ETHUSDT.LF.BB | subscribe | 315 ms |
| 2 | BTCUSDT.LF.BB | unsubscribe | 300 ms |
| 2 | ETHUSDT.LF.BB | unsubscribe | 300 ms |
| 3 | BTCUSDT.S.BB | subscribe | 314 ms |
| 3 | ETHUSDT.S.BB | subscribe | 312 ms |
| 3 | BTCUSDT.S.BB | unsubscribe | 301 ms |
| 3 | ETHUSDT.S.BB | unsubscribe | 300 ms |

Bybit sends subscribe/unsubscribe response (for both success and failure)

| market | msg/s | ≈ messages / day | ≈ top bid/ask changes / day |
|--------|------:|-----------------:|----------------------------:|
| Mainnet linear (`.LF.BB`) | 21.0 | ~1.82M | ~1.81M |
| Mainnet spot | 22.3 | ~1.93M | ~1.93M |
| Testnet linear (stresstest) | 0.50 | ~44k | ~33k |

Bybit testnet has 1 orderbook update for every 2 seconds. 

We have .BB for Bybit Main and .BBT for Bybit testnet.


### CTrader FIX Quote Subscription Latency.

| account | asset | op | delay | note |
|--------:|-------|----|------:|------|
| 1 | USDCAD | subscribe | ~44 ms | `:subscribe` → first MD snapshot (mdreq `3eOI_`) |
| 1 | EURUSD | subscribe | ~44 ms | `:subscribe` → first MD snapshot (mdreq `VOK0J`) |
| 1 | USDJPY | subscribe | ~44 ms | `:subscribe` → first MD snapshot (mdreq `kZ4Ni`) |
| 1 | USDCAD | unsubscribe | n/a | disable MD request sent; no ack |
| 1 | EURUSD | unsubscribe | n/a | disable MD request sent; no ack |
| 1 | USDJPY | unsubscribe | n/a | disable MD request sent; no ack |

FIX only sends subscribe/unsubscribe reject. There is no subscribe/unsubscribe confirm message.

## OMS limit buy-sell

Stresstest Performance Limit Buy/Sell


```
                                   quote tick  quote tick   bybit
scenario                           250         100          350

- get quote (near-limit-order)   250           100          350
- send quote 1
- wait for quote (fill 1)        250           100          350
- quote for fill 2               250           100          350
- wait between fills               0             0
  (quote needs more than 100ms)
- total                          750          300          1050

one order                        750          300          1050
two order                       1500          600          2100
reality:                        1500
```


clj -X:stresstest :account-id 2 :algo :limit-buy-sell

this stresstest goes to 
- account-id 2 which gives 1 fill with 5ms wait time.
- it uses feed-id 5 which gives prices in 10ms.
- so: 10ms for limit order. 10ms for fill1 5ms wait = 20-25ms (depending if 5ms hits) 
  or 40-50ms for two limit order. 
- actual results 43.77 ms.  



