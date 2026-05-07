Feature: Extended Market Data Ingestion (Phase 2a — The Senses)
  QuoteTick, MarketVolumeTick, and ReferenceDataRecord are encoded as SBE messages
  and published to the Aeron MARKET_DATA_CHANNEL where the ArbSequencer can dispatch them.

  Scenario: HKEX QuoteTick with IEP and bid/ask is normalised and published to Aeron
    Given the QuoteGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a quote arrives from HKEX for symbol "0700.HK" with IEP 380.50 bid 380.40 ask 380.60
    Then a QuoteTick SBE message is received on the channel
    And the IEP field is 3805000
    And the bid price field is 3804000
    And the ask price field is 3806000
    And the quote exchange field is "HKEX"

  Scenario: TAIFEX MarketVolumeTick with IEV and daily volume is published to Aeron
    Given the MarketVolumeGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a volume tick arrives from TAIFEX for symbol "2330.TW" with IEV 5000000 and daily volume 12000000
    Then a MarketVolumeTick SBE message is received on the channel
    And the IEV field is 5000000
    And the daily volume field is 12000000

  Scenario: CSI ReferenceDataRecord round-trips through Aeron
    Given the ReferenceDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a reference data record arrives for symbol "000300.SH" on CSI with lot size 100 tick size 100 and constituent weight 50000
    Then a ReferenceDataRecord SBE message is received on the channel
    And the lot size field is 100
    And the tick size field is 100
    And the constituent weight field is 50000
