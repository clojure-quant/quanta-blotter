

you implement persistence to datahike database.

use/modify  quanta.blotter.oms.db

data to be stored:
1. message has 
   :message/type (keyword)
   :message/date (instant) (needs to be generated if not set)
   :message/account-id (int)   (all messages have it)
   :message/asset  (not all messages have it)
   :message/data (the complete message persisted as edn)
   this data gets added only. 

2. order-status
   this is the output of oms.working_orders
   this gets added on the first working_order output.
   then there are updates to the order-status.
   when writing the orders to db, you need to get the db/id 
   from datahike, and then use that to write modifications to the db.

3. fill 
   this is detected currently in oms.open_positions.
   however you need to create oms.fill namespace that 
   returns a flow of fills. 
   this flow then needs to be used by oms.open_positions flow.
   each trade has a fill-id.
   fills are stored only once.

3. position
   this is the output of oms.open_positions.
   each position gets created once and then gets updated.
   so on write you need to get the db/id and the use it for 
   future writes.


please study the schema in quanta.blotter.oms.db and
adjust it if necessary.


stage 1:
make adjustment to the schema 
create demo data that will get written (a vector of :msg :fill :order :position)
create an in memory database and then you call quanta.oms.db/process
process will get a vector like [:trade trade1 :msg msg1 :order order :order order2]
so this are multiple transactiosn that need to be processed.

stage 2
make adjustment to the schema and read demo.data/channel-paper.edn
and process it with with quanta.blotter.oms.open-positions and
quanta.blotter.oms.working-orders and quanta.oms.fills. 
then you do an eduction, so that the message flow converts msg to
{:msg msg} and the trade flow to {:trade trade} etc.
then use quanta.blotter.util/mix to combine all flows to one
all writes should be done in blocks. use quanta.blotter.logger/time-buffered for this.

stage 3
write quant.blotter.oms.db-transactor/start-db-transactor [this db]
this come from quanta.blotter.oms.core/create-order-manager.
db is a db that is created with quanta.blotter.oms.db/trade-db-start

stage 4
add to demo.oms code to do trade-db-start and start-db-transactor.

ask  me questions before writing code. 








