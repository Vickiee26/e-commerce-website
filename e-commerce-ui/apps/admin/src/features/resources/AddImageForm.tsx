import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Field, TextInput } from '../../components/Field'
import { useToast } from '../../components/Toast'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { ImagePreview } from './ImagePreview'
import { useCreateResource } from './queries'

const imageSchema = z.object({
  url: z
    .string()
    .min(1, 'A URL is required')
    .max(1000, 'Must be 1000 characters or fewer')
    .regex(/^https?:\/\//, 'Must start with http:// or https://'),
  name: z.string().max(255, 'Must be 255 characters or fewer'),
  isPrimary: z.boolean(),
})

type ImageValues = z.infer<typeof imageSchema>

const IMAGE_FIELDS = ['url', 'name'] as const

export function AddImageForm({
  productId,
  hasPrimary,
}: {
  productId: string
  hasPrimary: boolean
}): ReactElement {
  const create = useCreateResource(productId)
  const showToast = useToast()
  const [formMessage, setFormMessage] = useState<string | null>(null)

  const { register, handleSubmit, setError, reset, watch, formState } = useForm<ImageValues>({
    resolver: zodResolver(imageSchema),
    // The first image should be the primary one without anyone having to think about it.
    defaultValues: { url: '', name: '', isPrimary: !hasPrimary },
  })

  const url = watch('url')

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    create.mutate(
      {
        url: values.url,
        name: values.name === '' ? undefined : values.name,
        // A free-form string capped at 30 chars; only images are supported here.
        type: 'image',
        isPrimary: values.isPrimary,
      },
      {
        onSuccess: () => {
          showToast('Image added')
          reset({ url: '', name: '', isPrimary: false })
        },
        onError: (error) =>
          setFormMessage(applyApiErrorToForm<ImageValues>(error, setError, IMAGE_FIELDS)),
      },
    )
  })

  return (
    <form className="mt-4 flex flex-col gap-4 border-t border-slate-200 pt-4" onSubmit={onSubmit} noValidate>
      <div className="grid gap-4 md:grid-cols-2">
        <Field
          label="Image URL"
          htmlFor="image-url"
          required
          error={formState.errors.url?.message}
          hint="There is no upload — paste a URL that is already hosted somewhere."
        >
          <TextInput
            id="image-url"
            inputMode="url"
            autoComplete="off"
            invalid={formState.errors.url !== undefined}
            {...register('url')}
          />
        </Field>

        <Field label="Label" htmlFor="image-name" error={formState.errors.name?.message}>
          <TextInput
            id="image-name"
            invalid={formState.errors.name !== undefined}
            {...register('name')}
          />
        </Field>
      </div>

      {url !== '' ? <ImagePreview url={url} alt="Preview" /> : null}

      <label className="flex min-h-11 items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" className="h-4 w-4" {...register('isPrimary')} />
        Make this the primary image
      </label>

      {formMessage !== null ? (
        <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
          {formMessage}
        </p>
      ) : null}

      <div className="flex justify-end">
        <Button type="submit" loading={create.isPending}>
          Add image
        </Button>
      </div>
    </form>
  )
}
