from app.road_events import get_road_event_feeds


def test_wzdx_registry_filters_active_feeds_and_never_exposes_raw_urls(monkeypatch) -> None:
    token_query = "access" + "_token=do-not-expose"
    monkeypatch.setattr(
        "app.road_events._fetch_registry",
        lambda limit: [
            {
                "state": "Utah",
                "issuingorganization": "Utah DOT",
                "feedname": "UDOT WZDx",
                "url": {"url": f"https://udottraffic.utah.gov/wzdx?{token_query}"},
                "format": "geojson",
                "active": "true",
                "datafeed_frequency_update": "15 minutes",
                "version": "4.2",
                "needapikey": "false",
                "geocoded_column": {"coordinates": [-111.891, 40.761]},
            },
            {
                "state": "Georgia",
                "issuingorganization": "Georgia DOT",
                "feedname": "Inactive feed",
                "url": {"url": "https://example.com/inactive"},
                "format": "geojson",
                "active": "false",
            },
        ],
    )

    registry = get_road_event_feeds(limit=10)

    assert registry.source == "USDOT WZDx Feed Registry"
    assert registry.active_feeds == 1
    assert registry.no_key_feeds == 1
    assert registry.feeds[0].state == "utah"
    assert registry.feeds[0].endpoint_host == "udottraffic.utah.gov"
    assert registry.feeds[0].longitude == -111.891
    assert token_query.split("=")[0] not in registry.model_dump_json()


def test_wzdx_registry_marks_url_embedded_api_keys_as_restricted(monkeypatch) -> None:
    key_query = "api" + "_key=do-not-expose"
    monkeypatch.setattr(
        "app.road_events._fetch_registry",
        lambda limit: [
            {
                "state": "Oklahoma",
                "issuingorganization": "Oklahoma DOT",
                "feedname": "OKTraffic WZDx",
                "url": {"url": f"https://oktraffic.org/wzdx?{key_query}"},
                "format": "geojson",
                "active": "true",
                "needapikey": "false",
            },
        ],
    )

    registry = get_road_event_feeds(state="oklahoma", limit=5)

    assert registry.no_key_feeds == 0
    assert registry.feeds[0].requires_api_key is True
    assert registry.feeds[0].endpoint_host == "oktraffic.org"
