/**
 * Playwright global teardown — reset the database after all tests
 * so subsequent runs start with a clean slate.
 */

import { resetDatabase } from "./helpers";

export default async function globalTeardown(): Promise<void> {
  await resetDatabase();
}
