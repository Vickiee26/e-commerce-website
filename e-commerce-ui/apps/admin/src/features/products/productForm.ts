import { z } from 'zod'

/**
 * Price stays a string in the form. The column is `numeric(12,2)` and the backend enforces
 * `@Digits(integer = 10, fraction = 2)`, which is a rule about the *written* value — a number
 * would have already lost the distinction between `10.99` and `10.999`.
 */
const PRICE_PATTERN = /^\d{1,10}(\.\d{1,2})?$/

export const productSchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
  price: z
    .string()
    .min(1, 'Price is required')
    .regex(PRICE_PATTERN, 'Use up to ten digits and at most two decimal places'),
  categoryId: z.string().min(1, 'Category is required'),
  categoryTypeId: z.string().min(1, 'Type is required'),
})

export type ProductFormValues = z.infer<typeof productSchema>

export const PRODUCT_FIELDS = [
  'name',
  'description',
  'price',
  'categoryId',
  'categoryTypeId',
] as const

export function priceToNumber(price: string): number {
  return Number(price)
}
