import { expect, test, type Page } from '@playwright/test'
import { RUN_ID, ensureCategory, goTo, signIn, visible } from './helpers'

const LAUNCH_CATEGORIES = [
  { name: 'Abaya', code: 'abaya' },
  { name: 'Hijabs', code: 'hijabs' },
  { name: 'Accessories', code: 'accessories' },
]

const PRODUCT_NAME = `E2E Abaya ${RUN_ID}`

test.describe.configure({ mode: 'serial' })

/**
 * One sign-in per project, not one per test, and one page shared by all four.
 *
 * The backend rate-limits POST /auth/login to five attempts a minute per client and path
 * (app.rate-limit.capacity=5). Signing in per test asks for eight in twenty-five seconds and the
 * sixth is refused — correct behaviour that the suite has to live with, not work around. Sharing
 * the page rather than a storageState is deliberate too: the session keeps its refresh token in
 * sessionStorage, which storageState does not carry.
 *
 * The context is built from the project's own `use` so each project keeps its viewport. The runner
 * DOES still apply its artifact machinery to a context made from the `browser` fixture, so the
 * config sets `trace: 'off'` and tracing is started by hand below, after sign-in — otherwise the
 * runner's own trace starts first, this start() throws "Tracing has been already started", and the
 * trace it did start holds ADMIN_PASSWORD. On a failure, open the journey trace named at the end.
 */
let page: Page

test.beforeAll(async ({ browser }, testInfo) => {
  const context = await browser.newContext(testInfo.project.use)
  page = await context.newPage()

  // Signing in BEFORE tracing starts, on purpose: a trace records the arguments of every action,
  // so a trace that spans signIn() holds ADMIN_PASSWORD in plaintext. Nothing after this point
  // ever types the password, so the trace is safe to keep.
  await signIn(page)
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
})

test.afterAll(async ({}, testInfo) => {
  const context = page.context()
  await context.tracing.stop({ path: testInfo.outputPath('journey-trace.zip') })
  await context.close()
})

test('seeds the three launch categories, each with a type of the same name', async () => {
  await goTo(page, 'Categories')

  for (const category of LAUNCH_CATEGORIES) await ensureCategory(page, category)

  for (const category of LAUNCH_CATEGORIES) {
    const card = page.getByRole('listitem', { name: category.name })
    await expect(card.getByText('No types — cannot hold products')).toBeHidden()
  }

  // A reload proves the session survives one and that the categories came from the server, not
  // from state this test happened to be holding.
  await page.reload()
  for (const category of LAUNCH_CATEGORIES) {
    await expect(page.getByRole('listitem', { name: category.name })).toBeVisible()
  }
})

test('takes a product from nothing to sellable', async () => {
  await goTo(page, 'Products')

  // The empty state offers a second identical link, so the first visible one is the header's.
  await visible(page.getByRole('link', { name: 'New product' })).first().click()
  await expect(page.getByRole('heading', { name: 'New product', level: 1 })).toBeVisible()

  await page.getByLabel('Name').fill(PRODUCT_NAME)
  await page.getByLabel('Description').fill('Created by the end-to-end suite.')
  await page.getByLabel('Price').fill('129.50')
  await page.getByLabel('Category').selectOption({ label: 'Abaya' })
  await page.getByLabel('Type').selectOption({ label: 'Abaya' })
  await page.getByRole('button', { name: 'Create product' }).click()

  await expect(page.getByRole('heading', { name: PRODUCT_NAME, level: 1 })).toBeVisible()
  await expect(page.getByText('No variants yet — customers cannot buy this product')).toBeVisible()

  // A variant with an opening balance.
  await page.getByRole('button', { name: 'Add variant' }).click()
  const variantDialog = page.getByRole('dialog')
  await variantDialog.getByLabel('Colour').fill('Black')
  await variantDialog.getByLabel('Size').fill('M')
  await variantDialog.getByLabel('Opening stock').fill('5')
  await variantDialog.getByRole('button', { name: 'Add variant' }).click()
  await expect(variantDialog).toBeHidden()

  const variant = page.getByRole('listitem', { name: 'Black / M' })
  await expect(variant).toBeVisible()
  await expect(page.getByText('No variants yet — customers cannot buy this product')).toBeHidden()

  // Every later change is a signed delta with a reason.
  await variant.getByRole('button', { name: 'Adjust stock for Black / M' }).click()
  const stockDialog = page.getByRole('dialog')
  await stockDialog.getByLabel('New quantity').fill('8')
  await expect(stockDialog.getByText('Adds 3 (5 → 8)')).toBeVisible()
  await stockDialog.getByLabel('Reason').fill(`E2E restock ${RUN_ID}`)
  await stockDialog.getByRole('button', { name: 'Adjust stock' }).click()
  await expect(stockDialog).toBeHidden()
  await expect(variant.getByText('8 in stock')).toBeVisible()

  // An image, which becomes the primary one because it is the first.
  await page.getByLabel('Image URL').fill('https://placehold.co/600x800/png')
  await page.getByLabel('Label').fill('Front')
  await page.getByRole('button', { name: 'Add image' }).click()
  const image = page.getByRole('listitem', { name: 'Front' })
  await expect(image.getByText('Primary')).toBeVisible()

  // The list reflects the computed columns the detail page never sends.
  await goTo(page, 'Products')
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  await expect(visible(page.getByRole('link', { name: PRODUCT_NAME }))).toBeVisible()
})

test('archives a product away from customers and restores it', async () => {
  await goTo(page, 'Products')
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  await visible(page.getByRole('link', { name: PRODUCT_NAME })).click()
  await expect(page.getByRole('heading', { name: PRODUCT_NAME, level: 1 })).toBeVisible()

  const productId = new URL(page.url()).pathname.split('/').pop() ?? ''
  expect(productId).not.toBe('')

  // The two header buttons are the only exact matches for these names — a variant's own
  // "Archive Black / M" is not. Waiting for the swap is what makes the checks below deterministic:
  // the header only changes after the mutation's refetch, so the server has already committed.
  const archiveButton = page.getByRole('button', { name: 'Archive product', exact: true })
  const restoreButton = page.getByRole('button', { name: 'Restore product', exact: true })

  // A customer can see it right now.
  await expect(archiveButton).toBeVisible()
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(200)

  await archiveButton.click()
  const archiveDialog = page.getByRole('dialog')
  await expect(archiveDialog).toContainText('Nothing is deleted and you can restore it later.')
  await archiveDialog.getByRole('button', { name: 'Archive' }).click()
  await expect(restoreButton).toBeVisible()

  // Criterion 10, the half no mocked test can prove: gone for customers, 404 rather than a 410 or
  // an empty body, because to a customer an archived product never existed.
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(404)

  // ...and gone from the admin default list, but findable under the archived filters.
  await goTo(page, 'Products')
  await page.getByLabel('Search products').fill(PRODUCT_NAME)
  await expect(visible(page.getByRole('link', { name: PRODUCT_NAME }))).toBeHidden()

  await page.getByLabel('Status').selectOption('only')
  await expect(visible(page.getByRole('link', { name: PRODUCT_NAME }))).toBeVisible()
  await page.getByLabel('Status').selectOption('all')
  await expect(visible(page.getByRole('link', { name: PRODUCT_NAME }))).toBeVisible()

  await visible(page.getByRole('link', { name: PRODUCT_NAME })).click()
  await restoreButton.click()
  const restoreDialog = page.getByRole('dialog')
  await expect(restoreDialog).toContainText('Variants archived separately stay archived.')
  await restoreDialog.getByRole('button', { name: 'Restore' }).click()

  await expect(archiveButton).toBeVisible()
  expect((await page.request.get(`/api/products/${productId}`)).status()).toBe(200)
})

test('shows the layout that fits the viewport', async ({}, testInfo) => {
  await goTo(page, 'Products')

  const table = page.getByRole('table')
  const openMenu = page.getByRole('button', { name: 'Open menu' })

  if (testInfo.project.name === 'mobile') {
    // 390px: cards, no table, and navigation behind the drawer.
    await expect(table).toBeHidden()
    await expect(openMenu).toBeVisible()

    await openMenu.click()
    await visible(page.getByRole('link', { name: 'Categories', exact: true })).click()
    await expect(page.getByRole('heading', { name: 'Categories', level: 1 })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Close menu' })).toBeHidden()

    // Nothing overflows sideways on a phone.
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(390)

    // Every visible control clears 44px. `min-h-11` is meant to guarantee this; measure it rather
    // than trust it, because a stray `h-8` in one component is invisible in review.
    const controls = await page.getByRole('button').all()
    for (const control of controls) {
      const box = await control.boundingBox()
      if (box !== null) expect(box.height).toBeGreaterThanOrEqual(44)
    }
  } else {
    // 1440px: the table, the permanent sidebar, no drawer trigger.
    await expect(table).toBeVisible()
    await expect(openMenu).toBeHidden()
    await expect(page.getByRole('link', { name: 'Categories', exact: true })).toBeVisible()
  }
})
