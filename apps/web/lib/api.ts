export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly requestId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/backend/${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as {
      detail?: string;
      requestId?: string;
    };
    throw new ApiError(
      response.status,
      problem.detail ?? "This action could not be completed.",
      problem.requestId,
    );
  }
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}
