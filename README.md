# quanta-blotter
orders executions positions with account/trader routing


order/create
order/cancel
order/modify
order-acq/order-created
order-acq/order-canceled
order-acq/order-modified
order-update/fill
order-update/expired
order-update/message

order-status/order-created
order-status/order-changed
order-status/order-done

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