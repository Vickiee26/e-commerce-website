import { zodResolver } from '@hookform/resolvers/zod'
import { isApiError } from '@shopflow/api-client'
import { useEffect, useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Field, TextInput, inputClass } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { CODE_PATTERN, toCode } from '../../lib/slug'
import { useCreateCategory } from './queries'

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
}: {
  open: boolean
  onClose: () => void
}): ReactElement | null {
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const [codeEdited, setCodeEdited] = useState(false)
  const create = useCreateCategory()
  const showToast = useToast()

  const { register, handleSubmit, setError, setValue, reset, watch, formState } =
    useForm<CategoryValues>({
      resolver: zodResolver(categorySchema),
      defaultValues: { name: '', code: '', description: '' },
    })

  const name = watch('name')

  // The code tracks the name until the operator takes it over, then it is theirs.
  useEffect(() => {
    if (!codeEdited) setValue('code', toCode(name))
  }, [name, codeEdited, setValue])

  // Reopening starts clean rather than inheriting a failed attempt.
  useEffect(() => {
    if (open) {
      reset({ name: '', code: '', description: '' })
      setCodeEdited(false)
      setFormMessage(null)
    }
  }, [open, reset])

  const codeRegistration = register('code')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        name: values.name,
        code: values.code,
        description: values.description === '' ? undefined : values.description,
      },
      {
        onSuccess: () => {
          showToast(`Category "${values.name}" created`)
          onClose()
        },
        onError: (error) => {
          // `code` is the only unique field on a category, so a 409 can only be about it. The
          // backend sends no errors[] for a conflict, so the mapping happens here.
          if (isApiError(error) && error.status === 409) {
            setError('code', {
              type: 'server',
              message: error.detail ?? 'That code is already used',
            })
            return
          }
          setFormMessage(applyApiErrorToForm<CategoryValues>(error, setError, CATEGORY_FIELDS))
        },
      },
    )
  })

  return (
    <Dialog
      open={open}
      title="New category"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={create.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="category-form" loading={create.isPending}>
            Create category
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
          hint="Lower-case letters, digits and single hyphens. Permanent once created."
        >
          <TextInput
            id="category-code"
            invalid={formState.errors.code !== undefined}
            {...codeRegistration}
            onChange={(event) => {
              setCodeEdited(true)
              void codeRegistration.onChange(event)
            }}
          />
        </Field>

        <Field label="Description" htmlFor="category-description" error={formState.errors.description?.message}>
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
