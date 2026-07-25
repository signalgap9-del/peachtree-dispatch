"""Full pipeline test: NL constraints -> VRP solver -> route results."""
import sys, json, os
sys.path.insert(0, "services/api")
os.environ.setdefault("PYTHONPATH", "services/api")

constraints = {
    "stops": [
        {"name": "Seattle", "type": "origin"},
        {"name": "Miami", "type": "destination"}
    ],
    "vehicle": {"type": "truck", "hazmat": True},
    "departure": {"earliest": "08:00"},
    "softConstraints": [
        {"type": "arrive_before", "target": "storm", "weight": 1.0},
        {"type": "avoid", "target": "highway", "weight": 0.7}
    ],
    "hardConstraints": [],
    "objective": "min_risk"
}

print("=" * 60)
print("FULL PIPELINE: NL Constraints -> VRP Solver -> Route")
print("=" * 60)
print("\n[Step 1] LLM-extracted constraints:")
print(json.dumps(constraints, indent=2))

# Step 2: Route comparison via directions engine
print("\n[Step 2] Running route engine (Seattle -> Miami, truck)...")
try:
    from app.directions import build_directions
    from app.models import DirectionsRequest, Place
    req = DirectionsRequest(
        origin=Place(place_id="seattle", city="Seattle", state="WA", latitude=47.6062, longitude=-122.3321, display_name="Seattle, WA"),
        destination=Place(place_id="miami", city="Miami", state="FL", latitude=25.7617, longitude=-80.1918, display_name="Miami, FL"),
        vehicle_type="TRUCK"
    )
    plan = build_directions(req)
    print(f"  Distance: {plan.distance_miles:.1f} miles")
    print(f"  Duration: {plan.duration_minutes:.0f} min ({plan.duration_minutes/60:.1f} hrs)")
    print(f"  Risk Score: {plan.risk_score}/100")
    print(f"  Climate Delay: {plan.climate_delay_minutes:.0f} min")
    print(f"  Alternatives: {len(plan.alternatives)}")
    print()
    for i, alt in enumerate(plan.alternatives):
        label = getattr(alt, "label", f"Route {i+1}")
        risk = getattr(alt, "risk_score", "N/A")
        print(f"  Alternative {i+1} [{label}]:")
        print(f"    Distance: {alt.distance_miles:.1f} mi")
        print(f"    Duration: {alt.duration_minutes:.0f} min")
        print(f"    Risk: {risk}")
    if plan.segments:
        print(f"\n  Route segments: {len(plan.segments)}")
        for seg in plan.segments[:5]:
            name = getattr(seg, "label", getattr(seg, "name", "?"))
            risk = getattr(seg, "risk_score", getattr(seg, "risk", "?"))
            hazard = getattr(seg, "primary_hazard", getattr(seg, "hazard", ""))
            print(f"    - {name}: risk={risk} {hazard}")
except Exception as e:
    print(f"  Error: {e}")
    import traceback
    traceback.print_exc()

# Step 3: NL2Opt formulation
print("\n[Step 3] NL2Opt formulation (constraints -> solver params)...")
try:
    from app.nl2opt.formulation import formulate_vrp
    from app.vrp.matrix import OsrmTableMatrixProvider
    from app.vrp.edge_risk import RuleBasedEdgeRiskProvider
    matrix_provider = OsrmTableMatrixProvider()
    edge_risk_provider = RuleBasedEdgeRiskProvider()
    result = formulate_vrp(constraints, matrix_provider, edge_risk_provider)
    print(f"  Objective: {result.objective}")
    print(f"  Nodes: {len(result.nodes)}")
    if hasattr(result, "edge_penalties") and result.edge_penalties:
        print(f"  Edge penalties applied: {len(result.edge_penalties)}")
        for (i, j), p in list(result.edge_penalties.items())[:3]:
            print(f"    - edge ({i},{j}): +{p}s")
    if hasattr(result, "explanations") and result.explanations:
        print(f"  Explanations:")
        for e in result.explanations:
            print(f"    - {e}")
except Exception as e:
    print(f"  Error: {e}")
    import traceback
    traceback.print_exc()

# Step 4: Multi-stop VRP (if available)
print("\n[Step 4] Multi-stop VRP test (3 stops)...")
try:
    from app.vrp.multi_stop import build_default_multi_stop_service
    from app.vrp.models import MultiStopRouteRequest, RouteStop
    svc = build_default_multi_stop_service()
    stops = [
        RouteStop(stop_id="seattle", name="Seattle", latitude=47.6062, longitude=-122.3321),
        RouteStop(stop_id="portland", name="Portland", latitude=45.5152, longitude=-122.6784),
        RouteStop(stop_id="san-francisco", name="San Francisco", latitude=37.7749, longitude=-122.4194),
    ]
    mreq = MultiStopRouteRequest(stops=stops, vehicle_type="TRUCK")
    mresult = svc.plan(mreq)
    print(f"  Result type: {type(mresult).__name__}")
    rd = mresult.model_dump() if hasattr(mresult, "model_dump") else vars(mresult)
    print(f"  {json.dumps(rd, indent=2, default=str, ensure_ascii=False)[:600]}")
except Exception as e:
    print(f"  Error: {e}")

print("\n" + "=" * 60)
print("Pipeline test complete")
print("=" * 60)
