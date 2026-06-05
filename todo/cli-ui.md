

you improve the current babashka driven cli-ui

1. on the left side there should be a status-panel (just 5 characters wide)
   the items are Trading (search a nice unicode symbol) with hotkey T
   and history (search a nice unicode symbol) with hotkey H. the status
   panel has a green background.

2. the current ui goes almost entirely into the history page.

3. the trading page should
   - subscribe to working order and open position flows (m/ap)
     via the flowy library.
   - on the top you display the table of open positions
     on the bottom you display the table of working orders.
   - the server should send every 250 milliseconds a snapshot of 
     open positions and working orders. (this is easy to do with missionary 
     library)
   - the tables get updated accordingly


ask me questions
write a plan
when I give go implement.




     





