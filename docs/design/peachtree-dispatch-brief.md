# Peachtree Dispatch Visual Brief

## Product Direction

Peachtree Routes is a map-first, climate-aware route-planning product for drivers
across the United States and operations teams. It supports cars, vans, and trucks and should feel
closer to a focused mapping or mobility product than a traditional CRM dashboard.

## Required Experience

- Real U.S. addresses, places, and nationwide road routes are visible on the map.
- Weather and climate risk are legible as operational layers, not decorative data.
- Route optimization decisions and tradeoffs can be inspected.
- Dispatchers can understand the current plan and act without leaving the map.
- The interface feels polished enough for a production SaaS portfolio project.
- Vehicle profiles affect optimization cost and estimated route duration.

## Reference Direction

Study the information hierarchy and interaction quality of products such as Google
Maps, Uber, and Airbnb. Use them as references only. Create an original visual
system and original production assets for Peachtree Dispatch.

The Apache-2.0 `Subhampreet/Google-Map-Clone` repository is a reference for the
familiar search, directions, geolocation, and transport-mode interaction model.
Its Mapbox token and implementation are not copied into this project; Peachtree
Routes continues to use MapLibre and open map data.

## Next Design Pass

Before the next major frontend redesign:

1. Capture and annotate 2-5 concrete reference screens.
2. Produce an original map-first desktop mockup using Open Design or image generation.
3. Review the mockup with the user.
4. Implement the approved direction and run browser-based visual QA.
