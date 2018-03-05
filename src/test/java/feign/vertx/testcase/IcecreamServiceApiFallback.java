package feign.vertx.testcase;

import feign.vertx.testcase.domain.Bill;
import feign.vertx.testcase.domain.Flavor;
import feign.vertx.testcase.domain.IceCreamOrder;
import feign.vertx.testcase.domain.Mixin;
import io.vertx.core.Future;

import java.util.Arrays;
import java.util.Collection;

public class IcecreamServiceApiFallback implements IcecreamServiceApi {
  /**
   * Fallback for getAvailableFlavors(). It will default to just Banana
   * @return
   */
  @Override
  public Future<Collection<Flavor>> getAvailableFlavors() {
    Future ret = Future.future();

    ret.complete(Arrays.asList(Flavor.BANANA)); // We will fallback to just Banana

    return ret;
  }

  @Override
  public Future<Collection<Mixin>> getAvailableMixins() {
    return null;
  }

  /**
   * This fallback receives an order and provides a price with an
   * automatic 10% off due to the real REST endpoint not being available
   *
   * @param order
   * @return Bill
   */
  @Override
  public Future<Bill> makeOrder(IceCreamOrder order) {
    Future ret = Future.future();

    // Verify the order is defined
    if ( null == order ) {
      throw new IllegalArgumentException("Order is invalid");
    }

    // Verify the order is valid (should never trip)
    if ( null == order.getBalls() || order.getBalls().size() < 0) {
      throw new IllegalArgumentException("Order has an invalid number of balls");
    }

    // We set a special fallback price of 10% off
    Bill bill = Bill.makeBill(order);
    bill.setPrice(bill.getPrice()*0.90f);

    ret.complete(bill);

    return ret;
  }

  /**
   * Test exception handling
   * @param orderId
   * @return
   */
  @Override
  public Future<IceCreamOrder> findOrder(int orderId) {
    throw new IllegalAccessError("This is to test the fallback handler exception handling logic");
  }

  @Override
  public Future<Void> payBill(Bill bill) {
    return null;
  }
}
