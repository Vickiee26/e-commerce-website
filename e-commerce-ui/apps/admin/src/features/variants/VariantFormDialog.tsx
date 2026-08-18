import { zodResolver } from '@hookform/resolvers/zod'
import type { AdminVariant } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { useCreateVariant, useUpdateVariant } from './queries'

/**
 * `openingStock` exists only on create. Edit mode has no stock control at all, because
 * `UpdateVariantRequest` has no field for it — the absence is the enforcement.
 */
const variantSchema = z.object({
  color: z.string().min(1, 'Colour is required').max(60, 'Must be 60 characters or fewer'),
  size: z.string().min(1, 'Size is required').max(30, 'Must be 30 characters or fewer'),
  openingStock: z.string().regex(/^\d{1,9}$/, 'Enter a whole number of items, 0 or more'),
})

type VariantValues = z.infer<typeof variantSchema>

const VARIANT_FIELDS = ['color', 'size'] as const

export function VariantFormDialog({
  open,
  onClose,
  productId,
  variant,
}: {
  open: boolean
  onClose: () => void
  productId: string
  variant?: AdminVariant
}): ReactElement | null {
  const editing = variant !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const create = useCreateVariant(productId)
  const update = useUpdateVariant(productId)
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, reset, formState } = useForm<VariantValues>({
    resolver: zodResolver(variantSchema),
    defaultValues: { color: '', size: '', openingStock: '0' },
  })

  useEffect(() => {
    if (!open) return
    reset({ color: variant?.color ?? '', size: variant?.size ?? '', openingStock: '0' })
    setFormMessage(null)
  }, [open, variant, reset])

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    const onError = (error: unknown): void =>
      setFormMessage(applyApiErrorToForm<VariantValues>(error, setError, VARIANT_FIELDS))

    if (variant !== undefined) {
      update.mutate(
        { variantId: variant.id, body: { color: values.color, size: values.size } },
        {
          onSuccess: () => {
            showToast('Variant updated')
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      { color: values.color, size: values.size, stockQuantity: Number(values.openingStock) },
      {
        onSuccess: () => {
          showToast('Variant added')
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit ${variant.color} / ${variant.size}` : 'New variant'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="variant-form" loading={pending}>
            {editing ? 'Save changes' : 'Add variant'}
          </Button>
        </>
      }
    >
      <form id="variant-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Colour" htmlFor="variant-color" required error={formState.errors.color?.message}>
          <TextInput
            id="variant-color"
            invalid={formState.errors.color !== undefined}
            {...register('color')}
          />
        </Field>

        <Field label="Size" htmlFor="variant-size" required error={formState.errors.size?.message}>
          <TextInput
            id="variant-size"
            invalid={formState.errors.size !== undefined}
            {...register('size')}
          />
        </Field>

        {editing ? (
          <p className="text-sm text-slate-600">
            Stock is changed with Adjust stock, so that every movement carries a reason.
          </p>
        ) : (
          <Field
            label="Opening stock"
            htmlFor="variant-opening-stock"
            required
            error={formState.errors.openingStock?.message}
            hint="The starting count. Every later change goes through Adjust stock."
          >
            <TextInput
              id="variant-opening-stock"
              inputMode="numeric"
              autoComplete="off"
              invalid={formState.errors.openingStock !== undefined}
              {...register('openingStock')}
            />
          </Field>
        )}

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
