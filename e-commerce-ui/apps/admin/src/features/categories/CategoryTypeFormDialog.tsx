import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError, type CategoryType } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategoryType, useUpdateCategoryType } from './queries'

const typeSchema = z.object({
  name: z.string().min(1, 'Name is required').max(255, 'Must be 255 characters or fewer'),
  code: z
    .string()
    .min(1, 'Code is required')
    .max(100, 'Must be 100 characters or fewer')
    .regex(CODE_PATTERN, 'Lower-case letters, digits and single hyphens only'),
  description: z.string().max(2000, 'Must be 2000 characters or fewer'),
})

type TypeValues = z.infer<typeof typeSchema>

const TYPE_FIELDS = ['name', 'code', 'description'] as const

export function CategoryTypeFormDialog({
  open,
  onClose,
  categoryId,
  categoryName,
  type,
}: {
  open: boolean
  onClose: () => void
  categoryId: string
  categoryName: string
  type?: CategoryType
}): ReactElement | null {
  const editing = type !== undefined
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategoryType()
  const update = useUpdateCategoryType()
  const showToast = useToast()
  const pending = create.isPending || update.isPending

  const { register, handleSubmit, setError, setValue, reset, watch, formState } = useForm<TypeValues>({
    resolver: zodResolver(typeSchema),
    defaultValues: { name: '', code: '', description: '' },
  })

  const name = watch('name')

  useEffect(() => {
    if (!editing && !codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, editing, setValue])

  useEffect(() => {
    if (!open) return
    reset({ name: type?.name ?? '', code: type?.code ?? '', description: type?.description ?? '' })
    setCodeEdited(false)
    setFormMessage(null)
  }, [open, type, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)

    const onError = (error: unknown): void => {
      // A type code is unique within its category, and that is the only conflict this call has.
      if (isApiError(error) && error.status === 409) {
        setError('code', {
          type: 'server',
          message: error.detail ?? 'That code is already used in this category',
        })
        return
      }
      setFormMessage(applyApiErrorToForm<TypeValues>(error, setError, TYPE_FIELDS))
    }

    if (type !== undefined) {
      update.mutate(
        { typeId: type.id, body: { name: values.name, description: values.description } },
        {
          onSuccess: () => {
            showToast(`Type "${values.name}" updated`)
            onClose()
          },
          onError,
        },
      )
      return
    }

    create.mutate(
      {
        categoryId,
        body: {
          name: values.name,
          code: values.code,
          description: values.description === '' ? undefined : values.description,
        },
      },
      {
        onSuccess: () => {
          showToast(`Type "${values.name}" added to ${categoryName}`)
          onClose()
        },
        onError,
      },
    )
  })

  return (
    <Dialog
      open={open}
      title={editing ? `Edit type ${type.name}` : `New type in ${categoryName}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="category-type-form" loading={pending}>
            {editing ? 'Save changes' : 'Add type'}
          </Button>
        </>
      }
    >
      <form id="category-type-form" className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Name" htmlFor="type-name" required error={formState.errors.name?.message}>
          <TextInput id="type-name" invalid={formState.errors.name !== undefined} {...register('name')} />
        </Field>

        <Field
          label="Code"
          htmlFor="type-code"
          required
          error={formState.errors.code?.message}
          hint={
            editing
              ? 'Permanent. Add a new type if you need a different code.'
              : 'Lower-case letters, digits and single hyphens. Unique within this category.'
          }
        >
          <TextInput
            id="type-code"
            disabled={editing}
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field label="Description" htmlFor="type-description" error={formState.errors.description?.message}>
          <textarea id="type-description" rows={3} className={inputClass} {...register('description')} />
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
