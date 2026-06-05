

I have 3 flow generators:
1. quanta.blotter.oms.flow.fill
2. quanta.blotter.oms.flow.open-positions
3. quanta.blotter.oms.flow.working-order

In the db I have a schema: quanta.blotter.oms.db

:fill/xxx for fill
:order/xxx for working-order
:position/xx for open-position

The goal is that this 3 flows generate the exact
same keywords that later will be stored in the db.

this means that the storage routine will need to be
changed and the quanta.blotter.db.print might need
to be changed.


on top I want new fields:

:order/date - needs to be added in flow.working-order
:fill/date - needs to be added in flow.fill
:position/date-open :position/date-close needs to be aded in flow.open-position

:order/text. when an order is cancelled, the text should say "cancelled",
when it is rejected the text should say "rejected [reason]", when it is
expired it should say "expired"

:position/qty should be renamed to :position/qty-open

:position/qty has the meaning of this is the maximum qty that all buys (for a long
positions) or sells (for a short position) were. the formula
for long: :position/pl-realized = :position/qty * (position/avg-entry-price - :position/avg-exit-price)
for long: :position/pl-realized = :position/qty * (:position/avg-exit-price :position/avg-entry-price - )

:position/side should either be :long or :short. :closed will not be used.
instead :position/open (boolean) will be used.

first analyze the code, ask me questions.
when I give the ok implement the plan.




  