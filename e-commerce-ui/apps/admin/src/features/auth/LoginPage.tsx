import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactElement } from 'react'
import { useForm } from 'react-hook-form'
import { useLocation, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button } from '../../components/Button'
import { Field, TextInput } from '../../components/Field'
import { applyApiErrorToForm } from '../../lib/formErrors'
import { NotAnAdminError, useLogin } from './session'

const loginSchema = z.object({
  email: z.email('Enter a valid email address').max(255, 'Must be 255 characters or fewer'),
  password: z
    .string()
    .min(1, 'Password is required')
    .max(72, 'Must be 72 characters or fewer'),
})

type LoginValues = z.infer<typeof loginSchema>

const LOGIN_FIELDS = ['email', 'password'] as const

export function LoginPage(): ReactElement {
  const navigate = useNavigate()
  const location = useLocation()
  const [formMessage, setFormMessage] = useState<string | null>(null)
  const signIn = useLogin()

  const { register, handleSubmit, setError, formState } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  const from = (location.state as { from?: string } | null)?.from ?? '/products'

  const onSubmit = handleSubmit((values) => {
    setFormMessage(null)
    signIn.mutate(values, {
      onSuccess: () => navigate(from, { replace: true }),
      onError: (error) => {
        setFormMessage(
          error instanceof NotAnAdminError
            ? error.message
            : applyApiErrorToForm<LoginValues>(error, setError, LOGIN_FIELDS),
        )
      },
    })
  })

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-sm flex-col justify-center gap-6 p-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">ShopFlow Admin</h1>
        <p className="mt-1 text-sm text-slate-600">Sign in to manage the catalogue.</p>
      </div>

      <form className="flex flex-col gap-4" onSubmit={onSubmit} noValidate>
        <Field label="Email" htmlFor="email" required error={formState.errors.email?.message}>
          <TextInput
            id="email"
            type="email"
            autoComplete="username"
            invalid={formState.errors.email !== undefined}
            aria-describedby={formState.errors.email !== undefined ? 'email-error' : undefined}
            {...register('email')}
          />
        </Field>

        <Field label="Password" htmlFor="password" required error={formState.errors.password?.message}>
          <TextInput
            id="password"
            type="password"
            autoComplete="current-password"
            invalid={formState.errors.password !== undefined}
            aria-describedby={formState.errors.password !== undefined ? 'password-error' : undefined}
            {...register('password')}
          />
        </Field>

        {formMessage !== null ? (
          <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">
            {formMessage}
          </p>
        ) : null}

        <Button type="submit" loading={signIn.isPending} className="w-full">
          Sign in
        </Button>
      </form>
    </main>
  )
}
