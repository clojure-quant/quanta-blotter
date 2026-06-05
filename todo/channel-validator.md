

I currently have quanta.blotter.consolidator with
(defn create-consolidator [{:keys [order orderupdate log] :as channel}]


Create quanta.blotter.oms.validation.channel.

The purpose of this is a sort of middleware that ensures that all orders/cancellations/modifications that are sent to brokers match the spec
and that only orderupdates that match the spec will be processed.

to do this we get a channel and return a channel.

terminology:
  input-channel (the channel that is provided)
  output-channel (the channel that gets crated)

order: 
read the output-channel order-rdv, if the received order does not match
the spec then put on the output orderupdate channel a order rejection
if it matches, then put the order to the input channel order-rdv.

orderupdate:
read the original orderupdate-rdv, if the received orderupdate does
ot match the spec, then put on the orderupdate channel a 
:type :orderupdate-spec-error :data original orderupdate.
if it matches, put the order to the output orderupdate.

ask me questions.

setup a unit test for this.

the unit test should define with m/seed original-orders and
with m/seed original-orderupdates.

then you have a feeder that feeds this seeded flows to the m/rdv
of order and orderupdate. 


