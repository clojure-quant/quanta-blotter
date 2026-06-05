
You improve the features of the paper-broker.

this is the current config.

{:account/id 3
 :account/api :paper
 :account/settings {:fill-probability 0
                    :wait-seconds 50}}

this is the new config:

{:account/id 3
 :account/api :paper
 :account/settings {:reject-probability 0
                    :fill-probability 0
                    :fill-qty-prct [100]
                    :wait-seconds 50}}

reject-probability. if it is 0 the broker will accept all orders
if it is between 0-99 it will randomly reject orders
the reject reasons can be one of "market-closed" "too-many-orders" and "temporary-broker-problem"
for a rejected order no fills will be sent, so after rejecting the paper-broker is done for that
order.

fill-probability: if this is 100, make sure that after wait-seconds the order will 
be guaranteed to be filled.

fill-qty-prct can be [100] or [50 50] or [50 25 25] or any other combination.
the numbers is a percentage of the original order quantity. 
so [50 25 25] means that the first fill will be for  50% of the order qty. the second fill 25%
and the final fill 25%.

ask questions.

write sensible unit tests







