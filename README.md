# quanta-blotter [![GitHub Actions status |clojure-quant/quanta-blotter](https://github.com/clojure-quant/quanta-blotter/workflows/CI/badge.svg)](https://github.com/clojure-quant/quanta-blotter/actions?workflow=CI)[![Clojars Project](https://img.shields.io/clojars/v/io.github.clojure-quant/quanta-blotter.svg)](https://clojars.org/io.github.clojure-quant/quanta-blotter)


[![GitHub Actions status |clojure-quant/quanta-blotter-cli](https://github.com/clojure-quant/quanta-blotter-cli/workflows/CI/badge.svg)](https://github.com/clojure-quant/quanta-blotter-cli/actions?workflow=CI)[![Clojars Project](https://img.shields.io/clojars/v/io.github.clojure-quant/quanta-blotter-cli.svg)](https://clojars.org/io.github.clojure-quant/quanta-blotter-cli)

orders executions positions with account/trader routing

DEMO

```
cd demo
clj -M:cli-server

in other terminal
bb tasks                (to find out which tasks are available)
bb client
bb send-orders fx1
bb send-orders qqq

```


## MESSAGE TYPES


* trader/new-order              place a new order
* trader/cancel-order           cancel existing order
trader/modify-order           modify existing order
trader/position-status

* broker/order-rejected         a new order is rejected
* broker/order-confirmed        a new order is confirmed (so it is working)
[broker/order-pending-new]    order received but not working yet. avoid if possible
* broker/order-canceled         an existing order is canceled (so it is no longer working)
broker/order-cancel-reject    cancel of existing order rejected (see :text for reason)
broker/order-modified         the order was modified (as per trader request)
* broker/order-filled           a fill or a partial fill "execution-report"
broker/order-expired          for example good-till-day order or fill-or-kill order 
broker/order-status           this is the order-status of the broker. avoid if possible. (will not get granular fills)
* broker/message
broker/session-message
broker/position-status
broker/balance-status
broker/margin-status

session/connected
session/disconnected

oms/order-status                 internally generated
oms/position-status              internally generated 

*universal-fx-message-types*

public enum UpdateType : short
{
	// Trader Requests
	TraderOrderSendRequest=1,
	TraderOrderCancelRequest=2,
			
	// Responses to TraderNewOrder,
	NewOrderAcq=3,
	Reject=4,
			
	// Responses to Cancel
	CancelAcq=5,
	OrderCancelReject = 6,
	Cancel=7,
			
	// Events that happen long after a new Order has been Aqnowledged
	// We need to differenciate between FillPartial and FillComplete, because FIX sends FillComplete after the
	//order is no longer working.
	FillPartial=8,
	FillComplete=9,
			
	Expiry=10
}

NOTES

- to use fill partial/complete for a broker that supports it is ok.
  however, if a broke does only send fill, then what does this mean?
  it means the broker-api needs to track if an order is open or not.
  this adds additional complexity to the broker-api.
  therefore the OMS needs to accept multiple fillpartial, and needs
  to gracefully interpret the last fillpartial as fillcomplete.

- we might be able to skip cancelacq and only go for cancel.


order-status
 :live
 :not-live
 :fill
 :all

OrderUpdateFilter
{
    All=1,
	FillComplete=2,
	FillPartial=8,
	Reject=3,
	Cancel=4,
	Expire=5,
	SendOrder=6,
	CancelOrder=7
}

public void onOrderUpdate(OrderUpdate orderUpdate)
		{
			//log.Info("PositionManager Order Update Received... " + orderUpdate.OrderID);
			
			Order order = null;
			string orderID = orderUpdate.OrderID;
			
			// Safety net, in case the OrderID is empty (this should never happen though)
			if (string.IsNullOrEmpty (orderID))
			{
				order = new Order (OrderData.NewUnknown ());
				orderID = Guid.NewGuid().ToString();
				order.OrderID = orderID;
				order.Notes = "Error: Empty OrderID";
			}
			
			//if (orderUpdate.Type == OrderUpdate.UpdateType.OrderCancelReject)
			//{
			//	orderUpdate.RejectUpdateType^
			//}
			
			
			// Normal Case: Find the already existing Order in the Order Dictionary.
			if (_OrdersDictionary.ContainsKey (orderID)) // Lookup Order based on the Order ID.
			{
				order = _OrdersDictionary[orderID];
			}
			
			// For Cancels: lookup Orders based on the Original Order ID
			/*if (order==null) // When Orders are Cancelled, they get a new cancel ID; and we need to look for the original Order ID.
			{
				if ( (orderUpdate.Type == OrderUpdate.UpdateType.Cancel) || (orderUpdate.Type == OrderUpdate.UpdateType.CancelAcq) )
				{
					if (!string.IsNullOrEmpty (orderUpdate.FIX_ClientOriginalOrderID))
					{
						if (_OrdersDictionary.ContainsKey (orderUpdate.FIX_ClientOriginalOrderID))
						{
							order = _OrdersDictionary[orderUpdate.FIX_ClientOriginalOrderID];
						}
					}
				}
			}*/
				
			// It is a new order.			
			if (order==null)
			{
				if ( ( (orderUpdate.Type == OrderUpdate.UpdateType.TraderOrderSendRequest) || (orderUpdate.Type == OrderUpdate.UpdateType.NewOrderAcq)  ) &&  (orderUpdate.OrderData != null) )
				{
					order = new Order (orderUpdate.OrderData);
					order.OrderID = orderUpdate.OrderID;
					order.SentTime = orderUpdate.Time; // The first time of the order is what is important.
				}
				else
				{
					// This should never happen; however some logfiles have a DateTime ordering bug,
					// and for those we need to have this in.
					order = new Order (OrderData.NewUnknown());
					order.OrderID = orderUpdate.OrderID;
				}
				_OrdersDictionary.Add (orderID, order);
				_Orders.Add(order);
			}
			
			// Make sure that the orderupdate links to the underlying order OR to the orderinfo from the FIX parser
			//if (orderUpdate.OrderData == null)
			//{
			//	orderUpdate.OrderData = order;
			//}

			_OrderUpdates.Add(orderUpdate);
			order.OnOrderUpdateReceived(orderUpdate);


            public enum UpdateType : short
		{
			// Trader Requests
			TraderOrderSendRequest=1,
			TraderOrderCancelRequest=2,
			
			// Responses to TraderNewOrder,
			NewOrderAcq=3,
			Reject=4,
			
			// Responses to Cancel
			CancelAcq=5,
			OrderCancelReject = 6,
			Cancel=7,
			
			// Events that happen long after a new Order has been Aqnowledged
			// We need to differenciate between FillPartial and FillComplete, because FIX sends FillComplete after the
			//order is no longer working.
			FillPartial=8,
			FillComplete=9,
			
			Expiry=10
		}


		(def order-status
  #{; create new order
    :order/new
    :order/order-confirm
    :order/reject

    ; cancel order
    :order/cancel-req
    :order/cancel-reject
    :order/cancel-confirm
    :order/cancelled

    ; trade
    :order/fill-partial
    :order/fill

    ; expired orders
    :order/expired})


	google antigravity
	