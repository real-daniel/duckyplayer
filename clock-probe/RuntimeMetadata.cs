using System.Diagnostics;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.Json;

namespace ClockProbe;

static class RuntimeMetadata
{
    public static string Inspect(int pid)
    {
        using var process = Process.GetProcessById(pid);
        var module = process.Modules.Cast<ProcessModule>().Single(m => m.ModuleName == "coreclr.dll");
        using var stream = File.OpenRead(module.FileName);
        using var pe = new PEReader(stream);
        var evidence = new StringBuilder();
        evidence.AppendLine($"Runtime: {module.FileName}");
        foreach (var entry in pe.ReadDebugDirectory())
            if (entry.Type == DebugDirectoryEntryType.CodeView)
            {
                var cv = pe.ReadCodeViewDebugDirectoryData(entry);
                evidence.AppendLine($"PDB: {cv.Guid}/{cv.Age}: {cv.Path}");
            }
        var exports = pe.GetSectionData(pe.PEHeaders.PEHeader!.ExportTableDirectory.RelativeVirtualAddress).GetContent(0, 40).ToArray();
        uint count = BitConverter.ToUInt32(exports, 24);
        if (count > 10000) throw new IOException("Invalid export count");
        int names = BitConverter.ToInt32(exports, 32), ordinals = BitConverter.ToInt32(exports, 36), functions = BitConverter.ToInt32(exports, 28);
        int descriptorRva = 0;
        for (int i = 0; i < count; i++)
        {
            int nameRva = BitConverter.ToInt32(pe.GetSectionData(names + i * 4).GetContent(0, 4).ToArray());
            var nameReader = pe.GetSectionData(nameRva).GetReader();
            var nameBytes = new List<byte>();
            while (nameReader.RemainingBytes > 0 && nameBytes.Count < 256)
            {
                byte next = nameReader.ReadByte(); if (next == 0) break; nameBytes.Add(next);
            }
            var name = Encoding.UTF8.GetString(nameBytes.ToArray());
            if (name != "DotNetRuntimeContractDescriptor") continue;
            int ordinal = BitConverter.ToUInt16(pe.GetSectionData(ordinals + i * 2).GetContent(0, 2).ToArray());
            descriptorRva = BitConverter.ToInt32(pe.GetSectionData(functions + ordinal * 4).GetContent(0, 4).ToArray());
            break;
        }
        if (descriptorRva == 0) return evidence.AppendLine("No exported contract descriptor.").ToString();
        using var reader = new Reader(pid);
        var header = reader.Bytes((ulong)module.BaseAddress + (uint)descriptorRva, 40);
        if (Encoding.ASCII.GetString(header, 0, 8) != "DNCCDAC\0" || (BitConverter.ToUInt32(header, 8) & 3) != 1)
            throw new IOException($"Unsupported contract descriptor format: {Convert.ToHexString(header)}; {evidence}");
        int size = BitConverter.ToInt32(header, 12);
        int pointerCount = BitConverter.ToInt32(header, 24);
        if (size < 1 || size > 4 * 1024 * 1024 || pointerCount < 0 || pointerCount > 100000)
            throw new IOException("Invalid contract descriptor bounds");
        ulong pointers = BitConverter.ToUInt64(header, 32);
        var json = reader.Bytes(BitConverter.ToUInt64(header, 16), size);
        using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(json).TrimEnd('\0'));
        evidence.AppendLine("Contract globals related to GC:");
        foreach (var property in doc.RootElement.GetProperty("globals").EnumerateObject())
        {
            if (!property.Name.Contains("GC", StringComparison.OrdinalIgnoreCase) && !property.Name.Contains("Heap", StringComparison.OrdinalIgnoreCase)) continue;
            evidence.AppendLine($"  {property.Name}: {property.Value.GetRawText()}");
            // Log resolved pointer entries only, without assuming undocumented field layouts.
            var value = property.Value;
            if (value.ValueKind == JsonValueKind.Array && value.GetArrayLength() > 0 && value[0].ValueKind == JsonValueKind.Array) value = value[0];
            if (value.ValueKind == JsonValueKind.Array && value.GetArrayLength() > 0 && value[0].ValueKind == JsonValueKind.Number && value[0].TryGetInt32(out int index) && index >= 0 && index < pointerCount)
            {
                ulong address = BitConverter.ToUInt64(reader.Bytes(pointers + (ulong)index * 8, 8));
                evidence.AppendLine($"    pointer[{index}]=0x{address:X}");
                try { evidence.AppendLine($"    first 8 bytes: {Convert.ToHexString(reader.Bytes(address, 8))}"); }
                catch (IOException) { evidence.AppendLine("    pointer not readable"); }
            }
        }
        return evidence.ToString();
    }
}
