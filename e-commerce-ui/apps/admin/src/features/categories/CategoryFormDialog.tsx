import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError, type Category } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategory, useUpdateCategory } from './queries'

const categorySchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  code: z
    .string()
    .min(1, 'Code is required')
    .max(100, 'Must be 100 characters or fewer')
    .regex(CODE_PATTERN, 'Lower-case letters, digits and single hyphens only'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
})

type CategoryValues = z.infer<typeof categorySchema>

const CATEGORY_FIELDS = ['name', 'code', 'description'] as const

export function CategoryFormDialog({
  open,
  onClose,
  category,
}: {
  open: boolean
  onClose: () => void
  category?: Category
}): ReactElement | null {
  const editing = category !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategory()
  const update = useUpdateCategory()
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, setValue, reset, watch, formState } =
    useForm<CategoryValues>({
      resolver: zodResolver(categorySchema),
      defaultValues: { name: '', code: '', description: '' },
    })

  const name = watch('name')

  // The code tracks the name until the operator takes it over — and never in edit mode, where the
  // code is fixed and re-slugging it would be a lie.
  useEffect(() => {
    if (!editing && !codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, editing, setValue])

  // Reopening starts from the record, not from a failed attempt.
  useEffect(() => {
    if (!open) return
    reset({
      name: category?.name ?? '',
      code: category?.code ?? '',
      description: category?.description ?? '',
    })
    setCodeEdited(false)
    setFormMessage(null)
  }, [open, category, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    const description = values.description === '' ? undefined : values.description

    const onError = (error: unknown): void => {
      // `code` is a category's only unique field, so a 409 can only be about it. The backend sends
      // no errors[] for a conflict, so the mapping happens here.
      if (isApiError(error) && error.status === 409) {
        setError('code', { type: 'server', message: error.detail ?? 'That code is already used' })
        return
      }
      setFormMessage(applyApiErrorToForm<CategoryValues>(error, setError, CATEGORY_FIELDS))
    }

    if (category !== undefined) {
      // Send description even when empty: the backend treats null as "leave alone" and '' as
      // "clear it", so '' is how the operator removes a description.
      update.mutate(
        { id: category.id, body: { name: values.name, description: values.description } },
        {
          onSuccess: () => {
            showToast(`Category "${values.name}" updated`)
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      { name: values.name, code: values.code, description },
      {
        onSuccess: () => {
          showToast(`Category "${values.name}" created`)
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit ${category.name}` : 'New category'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="category-form" loading={pending}>
            {editing ? 'Save changes' : 'Create category'}
          </Button>
        </>
      }
    >
      <form id="category-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Name" htmlFor="category-name" required error={formState.errors.name?.message}>
          <TextInput
            id="category-name"
            invalid={formState.errors.name !== undefined}
            {...register('name')}
          />
        </Field>

        <Field
          label="Code"
          htmlFor="category-code"
          required
          error={formState.errors.code?.message}
          hint={
            editing
              ? 'Permanent. Create a new category if you need a different code.'
              : 'Lower-case letters, digits and single hyphens. Permanent once created.'
          }
        >
          <TextInput
            id="category-code"
            disabled={editing}
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field
          label="Description"
          htmlFor="category-description"
          error={formState.errors.description?.message}
        >
          <textarea
            id="category-description"
            rows={3}
            className={inputClass}
            {...register('description')}
          />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}
