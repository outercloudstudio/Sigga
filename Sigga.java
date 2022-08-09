//Budget sigmaker for Ghidra
//@author lexika
//@category Functions
//@keybinding
//@menupath
//@toolbar

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.MemoryAccessException;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class Sigga extends GhidraScript {
    // Helper class to convert a string signature to bytes + mask, also acts as a container for them
    private static class ByteSignature {
        public ByteSignature(String signature) throws InvalidParameterException {
            parseSignature(signature);
        }

        private void parseSignature(String signature) throws InvalidParameterException {
            // Remove all whitespaces
            signature = signature.replaceAll(" ", "");

            if (signature.isEmpty()) {
                throw new InvalidParameterException("Signature cannot be empty");
            }

            final List<Byte> bytes = new LinkedList<>();
            final List<Byte> mask = new LinkedList<>();
            for (int i = 0; i < signature.length(); ) {
                // Do not convert wildcards
                if (signature.charAt(i) == '?') {
                    bytes.add((byte) 0);
                    mask.add((byte) 0);

                    i++;
                    continue;
                }

                try {
                    // Try to convert the hex string representation of the byte to the actual byte
                    bytes.add(Integer.decode("0x" + signature.substring(i, i + 2)).byteValue());
                } catch (NumberFormatException exception) {
                    throw new InvalidParameterException(exception.getMessage());
                }

                // Not a wildcard
                mask.add((byte) 1);

                i += 2;
            }

            // Lists -> Member arrays
            this.bytes = new byte[bytes.size()];
            this.mask = new byte[mask.size()];
            for (int i = 0; i < bytes.size(); i++) {
                this.bytes[i] = bytes.get(i);
                this.mask[i] = mask.get(i);
            }
        }

        public byte[] getBytes() {
            return bytes;
        }

        public byte[] getMask() {
            return mask;
        }

        private byte[] bytes;
        private byte[] mask;
    }

    private AddressSetView getCurrentFunctionBody() {
        FunctionManager functionManager = currentProgram.getFunctionManager();
        if (currentLocation == null) {
            return null;
        }

        Address address = currentLocation.getAddress();
        if (address == null) {
            return null;
        }

        Function function = functionManager.getFunctionContaining(address);
        if (function == null) {
            return null;
        }

        return function.getBody();
    }

    private String cleanSignature(String signature) {
        // Remove trailing whitespace
        signature = signature.strip();

        if (signature.endsWith("?")) {
            // Use recursion to remove wildcards at end
            return cleanSignature(signature.substring(0, signature.length() - 1));
        }

        return signature;
    }

    private String buildSignatureFromInstructions(InstructionIterator instructions) throws MemoryAccessException {
        StringBuilder signature = new StringBuilder();

        for (Instruction instruction : instructions) {
            // It seems that instructions that contain addresses which may change at runtime
            // are always something else then "fallthrough", so we just do this.
            // TODO: Do this more properly, like https://github.com/nosoop/ghidra_scripts/blob/master/makesig.py#L41
            if (instruction.isFallthrough()) {
                for (byte b : instruction.getBytes()) {
                    // %02X = byte -> hex string
                    signature.append(String.format("%02X ", b));
                }
            } else {
                for (byte b : instruction.getBytes()) {
                    signature.append("? ");
                }
            }
        }

        return signature.toString();
    }

    private String refineSignature(String signature, Address functionAddress) {
        // Strip trailing whitespaces and wildcards
        signature = cleanSignature(signature);

        // Remove last byte
        String newSignature = signature.substring(0, signature.length() - 2);

        // Try to find the new signature
        // We know the signature is valid and will at least be found once,
        // so no need to catch the InvalidParameterException or check for null
        Address foundAddress = findAddressForSignature(newSignature);

        // If the new signature is still unique, recursively refine it more
        if (foundAddress.equals(functionAddress)) {
            return refineSignature(newSignature, functionAddress);
        }

        // We cannot refine the signature anymore without making it not unique
        return signature;
    }

    private void createSignature() throws MemoryAccessException {
        // Get currently selected function's body
        AddressSetView functionBody = getCurrentFunctionBody();

        // If we have no function selected, fail
        if (functionBody == null) {
            printerr("Failed to create signature: No function selected\n");
            return;
        }

        // Get instructions for current function
        InstructionIterator instructions = currentProgram.getListing().getInstructions(functionBody, true);

        // Generate signature for whole function
        String signature = buildSignatureFromInstructions(instructions);

        // Try to find it once to make sure the first address found matches the one we generated it from
        // We know the signature is valid at this point, so no need to catch the InvalidParameterException
        if (!findAddressForSignature(signature).equals(functionBody.getMinAddress())) {
            // I don't see what other problem could cause this
            printerr("Failed to create signature: Function is (most likely) not big enough to create a unique signature\n");
            return;
        }

        // Try to make the signature as small as possible while still being the first one found
        // Also strip trailing whitespaces and wildcards
        // TODO: Make this faster - Depending on the program's size and the size of the signature (function body) this could take quite some time
        signature = refineSignature(signature, functionBody.getMinAddress());

        println(signature);
    }

    private Address findAddressForSignature(String signature) throws InvalidParameterException {
        // See class definition
        ByteSignature byteSignature = new ByteSignature(signature);

        // Try to find the signature
        return currentProgram.getMemory().findBytes(currentProgram.getMinAddress(), currentProgram.getMaxAddress(),
                byteSignature.getBytes(), byteSignature.getMask(), true, null);
    }

    private void findSignature(String signature) {
        Address address = null;
        try {
            address = findAddressForSignature(signature);
        } catch (InvalidParameterException exception) {
            printerr("Failed to find signature: " + exception.getMessage() + "\n");
        }

        if (address == null) {
            println("Signature not found");
            return;
        }

        if (!currentProgram.getFunctionManager().isInFunction(address)) {
            println("Warning: The address found is not inside a function");
        }

        println("Found signature at: " + address);
    }

    public void run() throws Exception {
        switch (askChoice("Sigga", "Choose a action to perform",
                Arrays.asList(
                        "Create signature",
                        "Find signature"
                ), "Create signature")) {
            case "Create signature":
                createSignature();
                break;
            case "Find signature":
                findSignature(askString("Sigga", "Enter signature to find:", ""));
                break;
        }
    }
}