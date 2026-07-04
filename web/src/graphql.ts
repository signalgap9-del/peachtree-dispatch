import { accessToken } from "./auth";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

export type GraphQLResponse<T> = {
  data?: T;
  errors?: Array<{ message: string; path?: Array<string | number>; extensions?: Record<string, unknown> }>;
};

export class GraphQLRequestError extends Error {
  constructor(
    message: string,
    readonly errors: NonNullable<GraphQLResponse<unknown>["errors"]>,
  ) {
    super(message);
    this.name = "GraphQLRequestError";
  }
}

export async function graphqlRequest<TData, TVariables extends Record<string, unknown> = Record<string, never>>(
  query: string,
  variables?: TVariables,
  options: RequestInit = {},
): Promise<TData> {
  const token = accessToken();
  const response = await fetch(`${API_URL}/graphql`, {
    ...options,
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers as Record<string, string> | undefined),
    },
    body: JSON.stringify({
      query,
      variables: variables ?? {},
    }),
  });
  const payload = await response.json() as GraphQLResponse<TData>;
  if (!response.ok) {
    throw new GraphQLRequestError(response.statusText || "GraphQL request failed", payload.errors ?? []);
  }
  if (payload.errors?.length) {
    throw new GraphQLRequestError(payload.errors[0].message, payload.errors);
  }
  if (!payload.data) {
    throw new GraphQLRequestError("GraphQL response did not include data", []);
  }
  return payload.data;
}
