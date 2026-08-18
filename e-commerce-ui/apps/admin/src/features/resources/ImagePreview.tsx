import { useEffect, useState, type ReactElement } from 'react'

/**
 * The only way to discover before saving that a URL is a 404 or an HTML page. `key={url}` remounts
 * on every change so a previously failed URL cannot leave the message behind.
 */
export function ImagePreview({ url, alt }: { url: string; alt: string }): ReactElement {
  const [failed, setFailed] = useState(false)

  useEffect(() => setFailed(false), [url])

  return (
    <div className="flex flex-col gap-2">
      <div className="flex h-32 w-32 items-center justify-center overflow-hidden rounded-md border border-slate-200 bg-slate-50">
        {failed ? (
          <span aria-hidden="true" className="text-2xl text-slate-400">
            ⚠
          </span>
        ) : (
          <img
            key={url}
            src={url}
            alt={alt}
            className="h-full w-full object-cover"
            onError={() => setFailed(true)}
          />
        )}
      </div>
      {failed ? (
        <p role="alert" className="text-sm text-red-800">
          That URL did not load as an image. Check it before saving.
        </p>
      ) : null}
    </div>
  )
}
