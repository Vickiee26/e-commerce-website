import { expect, type Locator, type Page } from '@playwright/test'

/** Distinguishes this run's products from every previous run's, in a database that keeps them. */
export const RUN_ID = Date.now().toString(36)

/**
 * Both responsive layouts are always in the DOM — the products table from `md` up and the cards
 * below it, the permanent sidebar and the drawer — and CSS alone decides which one a person can
 * see. Narrowing to the visible match keeps a single locator honest in both viewport projects,
 * where `.first()` would silently pick whichever one happens to come first in the markup.
 */
export function visible(locator: Locator): Locator {
  return locator.filter({ visible: true })
}

export async function signIn(page: Page): Promise<void> {
  const email = process.env.ADMIN_EMAIL
  const password = process.env.ADMIN_PASSWORD
  if (email === undefined || password === undefined) throw new Error('ADMIN_EMAIL / ADMIN_PASSWORD missing')

  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  // Landing on /products is the proof that login, /api/me and the ADMIN role check all passed.
  await expect(page.getByRole('heading', { name: 'Products', level: 1 })).toBeVisible()
}

/**
 * Navigates the way a person would, through whichever navigation their viewport gives them: at
 * 390px the sidebar is display:none and the drawer is not mounted until the menu is opened, so
 * clicking the sidebar link there would wait forever on something nobody can see.
 */
export async function goTo(page: Page, item: 'Products' | 'Categories'): Promise<void> {
  const openMenu = page.getByRole('button', { name: 'Open menu' })
  if (await openMenu.isVisible()) await openMenu.click()

  await visible(page.getByRole('link', { name: item, exact: true })).click()
  await expect(page.getByRole('heading', { name: item, level: 1 })).toBeVisible()
}

/**
 * Idempotent: a second run finds the card and does nothing. Creating a duplicate code would 409,
 * and the point of the seed is the end state, not the insert.
 */
export async function ensureCategory(
  page: Page,
  category: { name: string; code: string },
): Promise<void> {
  // The heading renders while the categories query is still pending, so counting cards straight
  // after it finds none, tries to create a category that already exists, and gets the 409 the
  // idempotence is there to avoid. Wait for the query to settle one way or the other first.
  await expect(
    page
      .getByRole('listitem')
      .or(page.getByRole('button', { name: 'Create your first category' }))
      .first(),
  ).toBeVisible()

  const card = page.getByRole('listitem').filter({ hasText: category.code })
  if ((await card.count()) === 0) {
    const create = page.getByRole('button', { name: 'Create your first category' })
    if ((await create.count()) > 0) await create.click()
    else await page.getByRole('button', { name: 'New category' }).click()

    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Name').fill(category.name)
    await expect(dialog.getByLabel('Code')).toHaveValue(category.code)
    await dialog.getByRole('button', { name: 'Create category' }).click()
    await expect(dialog).toBeHidden()
  }

  const saved = page.getByRole('listitem', { name: category.name })
  await expect(saved).toBeVisible()

  // One type per category, named after it — the flat shape the spec asks for.
  if (await saved.getByText(`${category.name} (${category.code})`).isVisible()) return

  await saved.getByRole('button', { name: `Add type to ${category.name}` }).click()
  const typeDialog = page.getByRole('dialog')
  await typeDialog.getByLabel('Name').fill(category.name)
  await typeDialog.getByRole('button', { name: 'Add type' }).click()
  await expect(typeDialog).toBeHidden()
  await expect(saved.getByText(`${category.name} (${category.code})`)).toBeVisible()
}
