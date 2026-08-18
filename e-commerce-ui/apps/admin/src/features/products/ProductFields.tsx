import type { Category } from '@shopflow/api-client'
import type { ReactElement } from 'react'
import type { UseFormReturn } from 'react-hook-form'
import { Link } from 'react-router'
import { Field, TextInput, inputClass } from '../../components/Field'
import type { ProductFormValues } from './productForm'

/**
 * The five fields both the create page and the detail page edit. The type select is derived from
 * the chosen category, which is what makes the 404 pair (a type from another category)
 * unrepresentable rather than merely validated.
 */
export function ProductFields({
  form,
  categories,
}: {
  form: UseFormReturn<ProductFormValues>
  categories: Category[]
}): ReactElement {
  const { register, watch, setValue, formState } = form
  const categoryId = watch('categoryId')
  const selected = categories.find((category) => category.id === categoryId)
  const types = selected?.types ?? []

  const categoryRegistration = register('categoryId')

  return (
    <div className="flex flex-col gap-4">
      <Field label="Name" htmlFor="product-name" required error={formState.errors.name?.message}>
        <TextInput
          id="product-name"
          invalid={formState.errors.name !== undefined}
          {...register('name')}
        />
      </Field>

      <Field
        label="Price"
        htmlFor="product-price"
        required
        error={formState.errors.price?.message}
        hint="US dollars, e.g. 129.50"
      >
        <TextInput
          id="product-price"
          inputMode="decimal"
          autoComplete="off"
          invalid={formState.errors.price !== undefined}
          {...register('price')}
        />
      </Field>

      <Field
        label="Category"
        htmlFor="product-category"
        required
        error={formState.errors.categoryId?.message}
      >
        <select
          id="product-category"
          className={inputClass}
          aria-invalid={formState.errors.categoryId !== undefined}
          {...categoryRegistration}
          onChange={(event) => {
            // A type from the old category would be a 404, so it goes the moment the category does.
            setValue('categoryTypeId', '', { shouldValidate: false })
            void categoryRegistration.onChange(event)
          }}
        >
          <option value="">Choose a category</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Type"
        htmlFor="product-type"
        required
        error={formState.errors.categoryTypeId?.message}
        hint={
          selected === undefined ? (
            'Choose a category first'
          ) : types.length === 0 ? (
            <>
              {`“${selected.name}” has no types yet. `}
              <Link to="/categories" className="font-medium underline">
                Add a type
              </Link>
              {' before creating a product here.'}
            </>
          ) : undefined
        }
      >
        <select
          id="product-type"
          className={inputClass}
          disabled={types.length === 0}
          aria-invalid={formState.errors.categoryTypeId !== undefined}
          {...register('categoryTypeId')}
        >
          <option value="">Choose a type</option>
          {types.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Description"
        htmlFor="product-description"
        error={formState.errors.description?.message}
      >
        <textarea
          id="product-description"
          rows={4}
          className={inputClass}
          {...register('description')}
        />
      </Field>
    </div>
  )
}
