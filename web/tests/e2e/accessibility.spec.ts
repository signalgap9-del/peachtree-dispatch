import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

import { installApiMocks } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

for (const path of ["/", "/dashboard", "/saved", "/alerts", "/map"]) {
  test(`${path} has no critical accessibility violations`, async ({ page }) => {
    await page.goto(path);

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .disableRules(["color-contrast"])
      .analyze();

    expect(results.violations.filter((violation) => violation.impact === "critical")).toEqual([]);
  });
}
