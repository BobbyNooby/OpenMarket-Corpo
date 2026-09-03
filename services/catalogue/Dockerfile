FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY *.csproj ./
RUN dotnet restore
COPY . .
# proto sources from the shared contracts dir (csproj references ../../contracts)
COPY --from=contracts . /contracts/
RUN dotnet publish -c Release -o /out

FROM mcr.microsoft.com/dotnet/aspnet:10.0
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1500 -d /app -s /usr/sbin/nologin appuser
COPY --from=build /out /app
WORKDIR /app
RUN chown -R appuser:appuser /app
USER appuser
ENV ASPNETCORE_URLS=http://+:8081
EXPOSE 8081
ENTRYPOINT ["dotnet", "/app/Catalogue.dll"]
