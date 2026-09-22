import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { Catalogue } from "./catalogue";

const navigation = vi.hoisted(() => ({ params: new URLSearchParams() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => navigation.params,
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock("./queries", () => ({
  useGarments: () => ({ data: { pages: [] }, isPending: false, isError: false }),
}));

beforeEach(() => {
  navigation.params = new URLSearchParams();
});

it("preserves advanced filter drafts when a view, category, or order navigation arrives", async () => {
  const user = userEvent.setup();
  const view = render(<Catalogue />);
  await user.click(screen.getByText("More filters", { exact: true }));
  await user.type(screen.getByRole("textbox", { name: "Brand" }), "Studio");
  await user.type(screen.getByRole("textbox", { name: "Colour" }), "Cream");
  navigation.params = new URLSearchParams("view=WORK&category=TOP&sort=NAME");
  view.rerender(<Catalogue />);
  expect(screen.getByRole("textbox", { name: "Brand" })).toHaveValue("Studio");
  expect(screen.getByRole("textbox", { name: "Colour" })).toHaveValue("Cream");
  const form = screen.getByRole("textbox", { name: "Brand" }).closest("form")!;
  const submitted = new FormData(form);
  expect(submitted.get("brand")).toBe("Studio");
  expect(submitted.get("colour")).toBe("Cream");
  expect(submitted.get("view")).toBe("WORK");
  expect(submitted.get("category")).toBe("TOP");
});

it("restores advanced filters when their saved URL values change or are cleared", async () => {
  navigation.params = new URLSearchParams("brand=Studio&colour=Cream");
  const user = userEvent.setup();
  const view = render(<Catalogue />);
  await user.click(screen.getByText("More filters", { exact: true }));
  navigation.params = new URLSearchParams("brand=Arket&colour=Olive");
  view.rerender(<Catalogue />);
  expect(screen.getByRole("textbox", { name: "Brand" })).toHaveValue("Arket");
  expect(screen.getByRole("textbox", { name: "Colour" })).toHaveValue("Olive");
  navigation.params = new URLSearchParams();
  view.rerender(<Catalogue />);
  expect(screen.getByRole("textbox", { name: "Brand" })).toHaveValue("");
  expect(screen.getByRole("textbox", { name: "Colour" })).toHaveValue("");
});
