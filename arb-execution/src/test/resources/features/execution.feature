Feature: Pre-Trade Risk and Order Execution

  Scenario: Fat-finger quantity check rejects oversized order
    Given a RiskGateway with maxQtyPerOrder 500 lots
    When an order is submitted for HSI.HK BUY 501 lots at price 190000000
    Then the order should be rejected with code 1

  Scenario: Fat-finger price check rejects extreme price deviation
    Given a RiskGateway with maxQtyPerOrder 500 lots
    And the last known price for HSI.HK is 190000000
    When an order is submitted for HSI.HK BUY 1 lots at price 218500000
    Then the order should be rejected with code 2

  Scenario: Position limit check rejects order exceeding net exposure
    Given a RiskGateway with maxQtyPerOrder 500 lots
    And the current net position for HSI.HK is 4900 lots
    When an order is submitted for HSI.HK BUY 200 lots at price 190000000
    Then the order should be rejected with code 3

  Scenario: Valid order passes all risk checks and is filled
    Given a RiskGateway with maxQtyPerOrder 500 lots
    When an order is submitted for HSI.HK SELL 10 lots at price 190000000
    Then the order should be filled

  Scenario: Basket of 3 legs is sliced into 3 individual orders
    Given a BasketSlicer
    When a basket order is submitted with 3 legs
    Then 3 individual orders should be dispatched
