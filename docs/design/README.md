# Frontend Design Workflow

This directory is the durable source of truth for Peachtree Dispatch visual design.
Major UI work must pass through the following stages before it is considered ready.

## 1. Write The Brief

Define the user, job to be done, primary workflow, required data, constraints, and
success criteria. State what must remain functional during the redesign.

## 2. Collect References

Collect 2-5 references from real products. For each reference, record:

- URL and product name
- screenshot or a description of the relevant screen
- the interaction, hierarchy, layout, or visual treatment worth studying
- what must not be copied

Screenshots are private references. Do not commit or ship third-party copyrighted
assets without permission.

## 3. Create A Visual Direction

Use Open Design, Figma, or the image-generation tool to create at least one
original mockup. The mockup must show the primary workflow, information hierarchy,
map or data visualization treatment, and desktop composition. Add mobile states
when the screen is user-facing on mobile.

Open Design is installed locally at:

`C:\Users\trist\.local\open-design\v0.9.0\Open Design.exe`

## 4. Approval Checkpoint

Show the reference summary and mockup to the user before major implementation.
Record the selected direction and notable decisions in this directory. Skip this
checkpoint only when the user explicitly requests immediate implementation.

## 5. Implement

Translate the approved direction into accessible, responsive application code.
Generated mockup pixels are guidance, not a replacement for semantic HTML, usable
controls, loading and error states, or production data integration.

## 6. Visual QA

Open the implemented screen in a browser and verify:

- desktop and mobile layouts
- primary workflow and interactive states
- data visualization readability
- consistency with the approved mockup
- no regressions to existing behavior

Keep final screenshots and any material design decisions here.

## Current Research Package

- [Product UX research](./product-ux-research.md)
- [Open-source evaluation](./open-source-evaluation.md)
- [Connected service flow](./connected-service-flow.md)
- [Connected service implementation](./connected-service-implementation.md)
- [Generated concept review](./mockups/README.md)
