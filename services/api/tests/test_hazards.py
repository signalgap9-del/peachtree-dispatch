from app.hazards import classify_event


def test_classifies_supported_hazard_families() -> None:
    assert classify_event("Flash Flood Warning") == "FLOOD"
    assert classify_event("Tornado Warning") == "TORNADO"
    assert classify_event("Hurricane Warning") == "TROPICAL_CYCLONE"
    assert classify_event("Red Flag Warning") == "WILDFIRE"
    assert classify_event("Dense Fog Advisory") == "VISIBILITY"
