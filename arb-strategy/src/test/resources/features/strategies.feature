Feature: Index Arbitrage Strategy Signal Validation

  Scenario: HkexBasisArb fires SELL when annualised basis exceeds entry threshold
    Given a HkexBasisArb strategy with entry threshold 5000 bps100 and exit threshold 1000 bps100
    When an FvUpdate arrives with annualisedBasisBps 32017
    Then the strategy should emit a SELL order

  Scenario: HkexBasisArb suppresses signal when basis is below exit threshold
    Given a HkexBasisArb strategy with entry threshold 5000 bps100 and exit threshold 1000 bps100
    When an FvUpdate arrives with annualisedBasisBps 500
    Then no order should be emitted

  Scenario: MhiHsiBasisArb detects spread on receiving both HSI and MHI prices
    Given a MhiHsiBasisArb strategy with threshold 200 bps100
    When a MarketDataTick arrives for HSI.HK with price 190000000
    And a MarketDataTick arrives for MHI.HK with price 37900000
    Then the strategy should emit an order

  Scenario: TwseEtfArb fires when ETF market price diverges from NAV
    Given a TwseEtfArb strategy for symbol 0050.TW with threshold 2000 bps100
    When a QuoteTick arrives for 0050.TW with IEP 18000000
    And an FvUpdate arrives for 0050.TW with navPerUnit 17000000
    Then the strategy should emit an order

  Scenario: VolSkewBasisArb uses adaptive threshold from IV-RV spread
    Given a VolSkewBasisArb strategy with base threshold 5000 and IV 3000000 RV 1500000
    When an FvUpdate arrives with annualisedBasisBps 10000
    Then the strategy should emit an order

  Scenario: SsfBasisArb fires when futures trade rich to fair value
    Given a SsfBasisArb strategy for SSF TSMC-SSF-TW spot 2330.TW
    When a MarketDataTick arrives for TSMC-SSF-TW with price 6010000
    And a MarketDataTick arrives for 2330.TW with price 5800000
    Then the strategy should emit a SELL order

  Scenario: HkCnIndexPairArb fires on z-score breach
    Given a HkCnIndexPairArb strategy for HSI.HK and CSI300.CN with entry z-score 200
    When a MarketDataTick arrives for HSI.HK with price 190000000
    And a MarketDataTick arrives for CSI300.CN with price 250000000
    Then the strategy should emit an order

  Scenario: CrossBorderEtfArb fires when CNH-adjusted basis exceeds threshold
    Given a CrossBorderEtfArb strategy for symbol 2822.HK with threshold 3000 and fx rate 91000
    When an FvUpdate arrives for 2822.HK with navPerUnit 10000000 futuresFv 10500000
    Then the strategy should emit an order
