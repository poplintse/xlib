import { z } from "zod";

export const MAX_TIMESTAMP_MS = 253_402_300_799_999;
export const MAX_PROGRESS_ITEMS = 100;

const uuid = z.uuid().transform((value) => value.toLowerCase());
const visibleText = z.string().refine(
  (value) => !Array.from(value).some((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 0x1f || code === 0x7f;
  }),
  "must not contain control characters",
);

export const normalizedEmailSchema = z
  .string()
  .trim()
  .transform((value) => value.toLowerCase())
  .pipe(z.email().max(254));

export const deviceSchema = z
  .object({
    deviceId: uuid,
    deviceName: visibleText.trim().min(1).max(80),
    platform: z.enum(["ios", "android"]),
    appVersion: visibleText.trim().min(1).max(40),
  })
  .strict();

export const startSyncSchema = z
  .object({
    email: normalizedEmailSchema,
    device: deviceSchema,
  })
  .strict();

export const deviceIdParamsSchema = z.object({ deviceId: uuid }).strict();
export const deviceIdHeaderSchema = uuid;

const progressItemSchema = z
  .object({
    bookHash: z.string().regex(/^[0-9a-f]{64}$/),
    fileSize: z.number().int().safe().positive(),
    offset: z.number().int().safe().nonnegative(),
    readAtMs: z.number().int().safe().nonnegative().max(MAX_TIMESTAMP_MS),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.offset > value.fileSize) {
      context.addIssue({
        code: "custom",
        path: ["offset"],
        message: "offset must be between 0 and fileSize",
      });
    }
  });

export const progressSyncSchema = z
  .object({
    items: z.array(progressItemSchema).min(1).max(MAX_PROGRESS_ITEMS),
  })
  .strict()
  .superRefine((value, context) => {
    const keys = new Set<string>();
    value.items.forEach((item, index) => {
      const key = `${item.bookHash}:${item.fileSize}`;
      if (keys.has(key)) {
        context.addIssue({
          code: "custom",
          path: ["items", index],
          message: "duplicate bookKey",
        });
      }
      keys.add(key);
    });
  });

export type StartSyncInput = z.infer<typeof startSyncSchema>;
export type DeviceInput = z.infer<typeof deviceSchema>;
export type ProgressSyncInput = z.infer<typeof progressSyncSchema>;
export type ProgressItemInput = ProgressSyncInput["items"][number];

export function firstValidationMessage(error: z.ZodError): string {
  return error.issues[0]?.message ?? "request validation failed";
}
