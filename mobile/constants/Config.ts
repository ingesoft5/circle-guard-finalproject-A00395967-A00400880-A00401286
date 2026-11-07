/**
 * Global Configuration for CircleGuard Mobile.
 *
 * Cada URL se puede sobreescribir con EXPO_PUBLIC_<X>_BASE_URL en tiempo de build.
 * - Desarrollo local (expo start): sin variables, se usa http://localhost:<puerto>.
 * - Imagen web (mobile/Dockerfile): rutas relativas /svc/<servicio>, que nginx enruta
 *   al backend. Así la misma imagen sirve en dev, stage y prod.
 *
 * Expo reemplaza estas variables al compilar solo si se leen con la forma literal
 * process.env.EXPO_PUBLIC_X; no usar desestructuración ni process.env[nombre].
 */
export const AUTH_BASE_URL = process.env.EXPO_PUBLIC_AUTH_BASE_URL ?? 'http://localhost:8180'; // Auth Service
export const DASHBOARD_BASE_URL = process.env.EXPO_PUBLIC_DASHBOARD_BASE_URL ?? 'http://localhost:8084'; // Dashboard Service
export const FILE_BASE_URL = process.env.EXPO_PUBLIC_FILE_BASE_URL ?? 'http://localhost:8085'; // File Service
export const FORM_BASE_URL = process.env.EXPO_PUBLIC_FORM_BASE_URL ?? 'http://localhost:8086'; // Form Service
export const GATEWAY_BASE_URL = process.env.EXPO_PUBLIC_GATEWAY_BASE_URL ?? 'http://localhost:8087'; // Gateway Service
export const IDENTITY_BASE_URL = process.env.EXPO_PUBLIC_IDENTITY_BASE_URL ?? 'http://localhost:8083'; // Identity Service
export const NOTIFICATION_BASE_URL = process.env.EXPO_PUBLIC_NOTIFICATION_BASE_URL ?? 'http://localhost:8082'; // Notification Service
export const PROMOTION_BASE_URL = process.env.EXPO_PUBLIC_PROMOTION_BASE_URL ?? 'http://localhost:8088'; // Promotion Service
