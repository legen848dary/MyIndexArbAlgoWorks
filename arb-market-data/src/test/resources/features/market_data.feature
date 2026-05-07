Feature: Market Data Ingestion
  The MarketDataGateway normalizes raw exchange ticks into SBE-encoded
  MarketDataTick messages and publishes them to the Aeron MARKET_DATA_CHANNEL.

  Scenario: HKEX tick is normalized and published to Aeron
    Given the MarketDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a tick arrives from HKEX for symbol "0700.HK" with price 380.50
    Then a MarketDataTick SBE message is received on the channel
    And the normalized price is 3805000
    And the exchange field is "HKEX"
    And the symbol field is "0700.HK"

  Scenario: TAIFEX tick is normalized and published to Aeron
    Given the MarketDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a tick arrives from TAIFEX for symbol "2330.TW" with price 910.00
    Then a MarketDataTick SBE message is received on the channel
    And the normalized price is 9100000
    And the exchange field is "TAIFEX"

  Scenario: CSI tick is normalized and published to Aeron
    Given the MarketDataGateway is running with an Aeron subscriber on MARKET_DATA_CHANNEL
    When a tick arrives from CSI for symbol "000300.SH" with price 3845.75
    Then a MarketDataTick SBE message is received on the channel
    And the normalized price is 38457500
    And the exchange field is "CSI"
