import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { GarmentForm } from "./garment-form";

it("focuses invalid name and never submits empty garment data", async () => {
  const user = userEvent.setup();
  const submit = vi.fn();
  render(<GarmentForm submit={submit} pending={false} />);
  await user.click(screen.getByRole("button", { name: "Add to your wardrobe" }));
  expect(await screen.findByText("Give this piece a name.")).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Piece name *" })).toHaveFocus();
  expect(submit).not.toHaveBeenCalled();
});

it("submits user-entered canonical metadata", async () => {
  const user = userEvent.setup();
  const submit = vi.fn();
  render(<GarmentForm submit={submit} pending={false} />);
  await user.type(screen.getByRole("textbox", { name: "Piece name *" }), "Cream knit");
  await user.selectOptions(screen.getByRole("combobox", { name: "Category *" }), "TOP");
  await user.click(screen.getByRole("button", { name: "Add to your wardrobe" }));
  expect(submit).toHaveBeenCalledWith(
    expect.objectContaining({ name: "Cream knit", category: "TOP" }),
    expect.anything(),
  );
});

it("keeps an unknown purchase price empty when reviewing a photo draft", async () => {
  const user = userEvent.setup();
  const submit = vi.fn();
  render(
    <GarmentForm
      initial={{
        name: "New piece",
        category: "OTHER",
        purchasePrice: null,
        purchaseCurrency: null,
      }}
      submit={submit}
      pending={false}
    />,
  );
  await user.clear(screen.getByRole("textbox", { name: "Piece name *" }));
  await user.type(screen.getByRole("textbox", { name: "Piece name *" }), "Olive shirt");
  await user.click(screen.getByRole("button", { name: "Save changes" }));
  expect(submit).toHaveBeenCalledWith(
    expect.objectContaining({ name: "Olive shirt", purchasePrice: null }),
    expect.anything(),
  );
});

it("keeps initial tags intact and allows clearing a suggested list", async () => {
  const user = userEvent.setup();
  const submit = vi.fn();
  render(
    <GarmentForm
      initial={{
        name: "Summer dress",
        category: "DRESS",
        seasonTags: ["Summer"],
        styleTags: ["Minimal", "Classic"],
      }}
      submit={submit}
      pending={false}
    />,
  );
  await user.clear(screen.getByRole("textbox", { name: "Style tags" }));
  await user.click(screen.getByRole("button", { name: "Save changes" }));
  expect(submit).toHaveBeenCalledWith(
    expect.objectContaining({ seasonTags: ["Summer"], styleTags: [] }),
    expect.anything(),
  );
});
