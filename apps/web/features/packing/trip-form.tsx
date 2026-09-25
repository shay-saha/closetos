"use client";

import { useId } from "react";
import { Button } from "@/components/ui/button";
import { weatherNames, seasonNames, tripDays, type Trip, type Weather, type Season } from "./types";

type Props = { trip: Trip; onChange: (trip: Trip) => void; disabled: boolean };
const optional = (value: string) => value.trim() || null;
const number = (value: string) => (value === "" ? Number.NaN : Number(value));
const temperature = (value: string) => (value === "" ? null : Number(value));

export function TripForm({ trip, onChange, disabled }: Props) {
  const seasonId = useId();
  const constraints = trip.constraints;
  function changeConstraints(change: Partial<Trip["constraints"]>) {
    onChange({ ...trip, constraints: { ...constraints, ...change } });
  }
  function changeDates(change: Partial<Pick<Trip, "startDate" | "endDate">>) {
    const updated = { ...trip, ...change };
    const days = tripDays(updated.startDate, updated.endDate);
    if (constraints.occasions.length === 1 && days > 0 && days <= 31)
      updated.constraints = { ...constraints, occasions: [{ ...constraints.occasions[0], days }] };
    onChange(updated);
  }
  return (
    <fieldset className="packing-trip-fields" disabled={disabled}>
      <legend className="sr-only">Trip configuration</legend>
      <section className="packing-section" aria-labelledby="trip-details-heading">
        <h2 id="trip-details-heading">Where are you going?</h2>
        <div className="form-grid">
          <label>
            Trip name *
            <input
              required
              maxLength={160}
              value={trip.name}
              onChange={(event) => onChange({ ...trip, name: event.target.value })}
            />
          </label>
          <label>
            Destination
            <input
              maxLength={200}
              value={trip.locationText ?? ""}
              onChange={(event) =>
                onChange({ ...trip, locationText: optional(event.target.value) })
              }
            />
          </label>
          <label>
            Start date *
            <input
              required
              type="date"
              value={trip.startDate}
              onChange={(event) => changeDates({ startDate: event.target.value })}
            />
          </label>
          <label>
            End date *
            <input
              required
              type="date"
              value={trip.endDate}
              onChange={(event) => changeDates({ endDate: event.target.value })}
            />
          </label>
          <label>
            Maximum pieces *
            <input
              required
              type="number"
              min={1}
              max={100}
              step={1}
              value={Number.isNaN(constraints.maximumGarments) ? "" : constraints.maximumGarments}
              onChange={(event) =>
                changeConstraints({ maximumGarments: number(event.target.value) })
              }
            />
          </label>
        </div>
        <p className="small">
          The limit counts every unique piece, including shoes and accessories.
        </p>
      </section>
      <section className="packing-section" aria-labelledby="trip-weather-heading">
        <h2 id="trip-weather-heading">Weather and season</h2>
        <p>
          Choose the conditions you expect, or enter a temperature range. Compatibility uses your
          pieces’ recorded tags. Missing tags cannot establish suitability.
        </p>
        <div className="packing-checkboxes">
          {(Object.entries(weatherNames) as [Weather, string][]).map(([key, name]) => (
            <label key={key}>
              <input
                type="checkbox"
                checked={constraints.weather.assumptions.includes(key)}
                onChange={(event) =>
                  changeConstraints({
                    weather: {
                      ...constraints.weather,
                      assumptions: event.target.checked
                        ? [...constraints.weather.assumptions, key]
                        : constraints.weather.assumptions.filter((value) => value !== key),
                    },
                  })
                }
              />
              {name}
            </label>
          ))}
        </div>
        <div className="form-grid">
          <label>
            Minimum temperature (°C)
            <input
              type="number"
              min={-50}
              max={60}
              step={1}
              value={constraints.weather.minimumTemperatureC ?? ""}
              onChange={(event) =>
                changeConstraints({
                  weather: {
                    ...constraints.weather,
                    minimumTemperatureC: temperature(event.target.value),
                  },
                })
              }
            />
          </label>
          <label>
            Maximum temperature (°C)
            <input
              type="number"
              min={-50}
              max={60}
              step={1}
              value={constraints.weather.maximumTemperatureC ?? ""}
              onChange={(event) =>
                changeConstraints({
                  weather: {
                    ...constraints.weather,
                    maximumTemperatureC: temperature(event.target.value),
                  },
                })
              }
            />
          </label>
          <div className="packing-field">
            <label htmlFor={seasonId}>Season</label>
            <select
              id={seasonId}
              value={constraints.weather.season ?? ""}
              onChange={(event) =>
                changeConstraints({
                  weather: {
                    ...constraints.weather,
                    season: (event.target.value || null) as Season | null,
                  },
                })
              }
            >
              <option value="">No season constraint</option>
              {(Object.entries(seasonNames) as [Season, string][]).map(([key, name]) => (
                <option key={key} value={key}>
                  {name}
                </option>
              ))}
            </select>
          </div>
        </div>
        <p className="small">
          Cold means below 10°C, mild means 10–24°C, and hot means above 24°C. Cold or rain requires
          a compatible outer layer in every outfit. Rain also requires a recorded rain or waterproof
          tag on that layer.
        </p>
      </section>
      <section className="packing-section" aria-labelledby="trip-occasions-heading">
        <h2 id="trip-occasions-heading">What will you be doing?</h2>
        <p>
          Each trip day gets one complete outfit. Occasions cover the trip in the order entered.
          Optional tags and formality must match recorded metadata.
        </p>
        {constraints.occasions.map((occasion, index) => (
          <fieldset className="packing-occasion" key={index}>
            <legend>Occasion {index + 1}</legend>
            <div className="form-grid">
              <label>
                Occasion name *
                <input
                  required
                  maxLength={80}
                  value={occasion.name}
                  onChange={(event) =>
                    changeConstraints({
                      occasions: constraints.occasions.map((entry, position) =>
                        position === index ? { ...entry, name: event.target.value } : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Days *
                <input
                  required
                  type="number"
                  min={1}
                  max={31}
                  step={1}
                  value={Number.isNaN(occasion.days) ? "" : occasion.days}
                  onChange={(event) =>
                    changeConstraints({
                      occasions: constraints.occasions.map((entry, position) =>
                        position === index ? { ...entry, days: number(event.target.value) } : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Occasion tag
                <input
                  maxLength={60}
                  value={occasion.occasionTag ?? ""}
                  placeholder="For example, work"
                  onChange={(event) =>
                    changeConstraints({
                      occasions: constraints.occasions.map((entry, position) =>
                        position === index
                          ? { ...entry, occasionTag: optional(event.target.value) }
                          : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Formality
                <input
                  maxLength={60}
                  value={occasion.formality ?? ""}
                  placeholder="For example, casual"
                  onChange={(event) =>
                    changeConstraints({
                      occasions: constraints.occasions.map((entry, position) =>
                        position === index
                          ? { ...entry, formality: optional(event.target.value) }
                          : entry,
                      ),
                    })
                  }
                />
              </label>
            </div>
            {constraints.occasions.length > 1 && (
              <Button
                variant="quiet"
                aria-label={`Remove occasion ${index + 1}`}
                onClick={() =>
                  changeConstraints({
                    occasions: constraints.occasions.filter((_, position) => position !== index),
                  })
                }
              >
                Remove occasion
              </Button>
            )}
          </fieldset>
        ))}
        <Button
          variant="secondary"
          disabled={constraints.occasions.length >= 8}
          onClick={() =>
            changeConstraints({
              occasions: [
                ...constraints.occasions,
                { name: "", days: 1, occasionTag: null, formality: null },
              ],
            })
          }
        >
          Add occasion
        </Button>
      </section>
      <section className="packing-section" aria-labelledby="trip-events-heading">
        <h2 id="trip-events-heading">Any formal events?</h2>
        <p>These add an outfit on the event date, alongside the day’s regular occasion.</p>
        {constraints.formalEvents.map((event, index) => (
          <fieldset className="packing-occasion" key={index}>
            <legend>Formal event {index + 1}</legend>
            <div className="form-grid">
              <label>
                Event name *
                <input
                  required
                  maxLength={80}
                  value={event.name}
                  onChange={(input) =>
                    changeConstraints({
                      formalEvents: constraints.formalEvents.map((entry, position) =>
                        position === index ? { ...entry, name: input.target.value } : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Event date *
                <input
                  required
                  type="date"
                  min={trip.startDate}
                  max={trip.endDate}
                  value={event.date}
                  onChange={(input) =>
                    changeConstraints({
                      formalEvents: constraints.formalEvents.map((entry, position) =>
                        position === index ? { ...entry, date: input.target.value } : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Event occasion tag
                <input
                  maxLength={60}
                  value={event.occasionTag ?? ""}
                  onChange={(input) =>
                    changeConstraints({
                      formalEvents: constraints.formalEvents.map((entry, position) =>
                        position === index
                          ? { ...entry, occasionTag: optional(input.target.value) }
                          : entry,
                      ),
                    })
                  }
                />
              </label>
              <label>
                Required formality *
                <input
                  required
                  maxLength={60}
                  value={event.formality}
                  onChange={(input) =>
                    changeConstraints({
                      formalEvents: constraints.formalEvents.map((entry, position) =>
                        position === index ? { ...entry, formality: input.target.value } : entry,
                      ),
                    })
                  }
                />
              </label>
            </div>
            <Button
              variant="quiet"
              aria-label={`Remove formal event ${index + 1}`}
              onClick={() =>
                changeConstraints({
                  formalEvents: constraints.formalEvents.filter(
                    (_, position) => position !== index,
                  ),
                })
              }
            >
              Remove event
            </Button>
          </fieldset>
        ))}
        <Button
          variant="secondary"
          disabled={constraints.formalEvents.length >= 16}
          onClick={() =>
            changeConstraints({
              formalEvents: [
                ...constraints.formalEvents,
                { name: "", date: trip.startDate, occasionTag: null, formality: "" },
              ],
            })
          }
        >
          Add formal event
        </Button>
      </section>
      <section className="packing-section" aria-labelledby="trip-laundry-heading">
        <h2 id="trip-laundry-heading">Laundry and rewear</h2>
        <div className="form-grid">
          <label>
            Laundry every (days)
            <input
              type="number"
              min={0}
              max={31}
              step={1}
              value={Number.isNaN(constraints.laundryEveryDays) ? "" : constraints.laundryEveryDays}
              onChange={(event) =>
                changeConstraints({ laundryEveryDays: number(event.target.value) })
              }
            />
          </label>
          <label>
            Maximum wears between washes *
            <input
              required
              type="number"
              min={1}
              max={31}
              step={1}
              value={
                Number.isNaN(constraints.maximumWearsBetweenLaundry)
                  ? ""
                  : constraints.maximumWearsBetweenLaundry
              }
              onChange={(event) =>
                changeConstraints({ maximumWearsBetweenLaundry: number(event.target.value) })
              }
            />
          </label>
        </div>
        <p className="small">
          Use 0 for no laundry. An interval of 2 means laundry is complete before day 3, then before
          day 5. Tops, bottoms, and dresses use one wear per outfit; shoes and outer layers may be
          reused freely.
        </p>
      </section>
    </fieldset>
  );
}
